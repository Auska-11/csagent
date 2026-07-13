package com.smartcs.smart_cs_agent.capability.idempotency.jdbc;

import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.api.CapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.capability.idempotency.core.CapabilityResultCodec;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyExecutionInProgressException;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyKeyConflictException;
import com.smartcs.smart_cs_agent.capability.idempotency.core.IdempotencyWaitDeadlineExceededException;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 使用 MySQL 唯一键和短租约协调多个 Agent 实例的副作用调用。
 *
 * <p>唯一键决定“谁先执行”，租约解决执行实例崩溃后记录永久卡在 IN_PROGRESS 的问题，COMPLETED
 * 记录保存可回放结果。这个实现不会把 storageKey 原文写入数据库，只保存 SHA-256，减少会话和
 * 用户标识在幂等表中的重复暴露。</p>
 */
@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
public class JdbcCapabilityIdempotencyStore implements CapabilityIdempotencyStore {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";
    private static final ScheduledExecutorService LEASE_RENEWAL_EXECUTOR =
            Executors.newScheduledThreadPool(2, daemonThreadFactory());

    private final JdbcDatabaseClient databaseClient;
    private final CapabilityResultCodec resultCodec;
    private final long leaseMs;
    private final long waitTimeoutMs;
    private final long pollIntervalMs;

    public JdbcCapabilityIdempotencyStore(
            JdbcDatabaseClient databaseClient,
            CapabilityResultCodec resultCodec,
            @Value("${smartcs.capability.idempotency.lease-ms:60000}") long leaseMs,
            @Value("${smartcs.capability.idempotency.wait-timeout-ms:30000}") long waitTimeoutMs,
            @Value("${smartcs.capability.idempotency.poll-interval-ms:50}") long pollIntervalMs) {
        if (leaseMs <= 0 || waitTimeoutMs <= 0 || pollIntervalMs <= 0) {
            throw new IllegalArgumentException("Idempotency lease and wait settings must be positive");
        }
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient cannot be null");
        this.resultCodec = Objects.requireNonNull(resultCodec, "resultCodec cannot be null");
        this.leaseMs = leaseMs;
        this.waitTimeoutMs = waitTimeoutMs;
        this.pollIntervalMs = pollIntervalMs;
        initTable();
    }

    private void initTable() {
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_capability_idempotency (
                    storage_key_hash CHAR(64) NOT NULL,
                    request_fingerprint_hash CHAR(64) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    owner_token VARCHAR(64) NOT NULL,
                    lease_until BIGINT NOT NULL,
                    result_json LONGTEXT NULL,
                    result_schema_version INT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (storage_key_hash),
                    INDEX idx_cs_capability_idempotency_updated_at (updated_at)
                )
                """);
    }

    @Override
    public CapabilityResult<?> execute(String storageKey,
                                       String requestFingerprint,
                                       Supplier<CapabilityResult<?>> operation) {
        return execute(storageKey, requestFingerprint, operation, null);
    }

    @Override
    public CapabilityResult<?> execute(String storageKey,
                                       String requestFingerprint,
                                       Supplier<CapabilityResult<?>> operation,
                                       Instant requestDeadline) {
        requireText(storageKey, "storageKey cannot be empty");
        requireText(requestFingerprint, "requestFingerprint cannot be empty");
        Objects.requireNonNull(operation, "operation cannot be null");

        String keyHash = sha256(storageKey);
        String fingerprintHash = sha256(requestFingerprint);
        String ownerToken = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        long configuredWaitDeadline = safeAdd(startedAt, waitTimeoutMs);
        long requestWaitDeadline = requestDeadline == null
                ? Long.MAX_VALUE
                : epochMilliOrBoundary(requestDeadline);
        long waitDeadline = Math.min(configuredWaitDeadline, requestWaitDeadline);

        if (startedAt >= waitDeadline) {
            throwWaitLimit(storageKey, requestWaitDeadline, configuredWaitDeadline);
        }

        while (true) {
            long now = System.currentTimeMillis();
            if (tryCreateClaim(keyHash, fingerprintHash, ownerToken, now)) {
                return executeAsOwner(keyHash, fingerprintHash, ownerToken, operation);
            }

            Optional<StoredRecord> optional = load(keyHash);
            if (optional.isEmpty()) {
                // 另一个实例可能刚好因执行器异常、且尚未形成 CapabilityResult 而释放记录。
                // 重新竞争即可；已形成的 SUCCESS/拒绝/技术结果都会保存为 COMPLETED。
                continue;
            }

            StoredRecord existing = optional.get();
            if (!existing.requestFingerprintHash().equals(fingerprintHash)) {
                throw new IdempotencyKeyConflictException(storageKey);
            }
            if (COMPLETED.equals(existing.status())) {
                return decodeReplay(existing);
            }
            if (!IN_PROGRESS.equals(existing.status())) {
                throw new IllegalStateException(
                        "Unknown capability idempotency status: " + existing.status()
                );
            }

            // 持有者进程可能已经崩溃。只有租约过期后才允许一个新实例原子接管；
            // UPDATE 中带旧 ownerToken 和 leaseUntil，避免两个接管者同时成功。
            if (existing.leaseUntil() <= now
                    && tryTakeOver(keyHash, fingerprintHash, existing, ownerToken, now)) {
                return executeAsOwner(keyHash, fingerprintHash, ownerToken, operation);
            }

            if (now >= waitDeadline) {
                throwWaitLimit(storageKey, requestWaitDeadline, configuredWaitDeadline);
            }
            sleep(Math.min(pollIntervalMs, Math.max(1, waitDeadline - now)));
        }
    }

    private boolean tryCreateClaim(String keyHash,
                                   String fingerprintHash,
                                   String ownerToken,
                                   long now) {
        try {
            return databaseClient.update("""
                    INSERT INTO cs_capability_idempotency(
                        storage_key_hash, request_fingerprint_hash, status,
                        owner_token, lease_until, result_json, result_schema_version,
                        created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?)
                    """,
                    keyHash,
                    fingerprintHash,
                    IN_PROGRESS,
                    ownerToken,
                    now + leaseMs,
                    now,
                    now) == 1;
        } catch (JdbcDuplicateKeyException duplicateKey) {
            // 精确识别数据库唯一键冲突。不能使用“插入失败后再查一次”的方式判断，因为原 owner
            // 可能恰好在两步之间删除记录，导致正常竞争被误报为数据库故障。
            return false;
        }
    }

    private boolean tryTakeOver(String keyHash,
                                String fingerprintHash,
                                StoredRecord existing,
                                String newOwnerToken,
                                long now) {
        return databaseClient.update("""
                UPDATE cs_capability_idempotency
                SET owner_token = ?, lease_until = ?, updated_at = ?
                WHERE storage_key_hash = ?
                  AND request_fingerprint_hash = ?
                  AND status = ?
                  AND owner_token = ?
                  AND lease_until = ?
                """,
                newOwnerToken,
                now + leaseMs,
                now,
                keyHash,
                fingerprintHash,
                IN_PROGRESS,
                existing.ownerToken(),
                existing.leaseUntil()) == 1;
    }

    private CapabilityResult<?> executeAsOwner(String keyHash,
                                               String fingerprintHash,
                                               String ownerToken,
                                               Supplier<CapabilityResult<?>> operation) {
        ScheduledFuture<?> leaseRenewal = startLeaseRenewal(keyHash, fingerprintHash, ownerToken);
        try {
            CapabilityResult<?> result;
            try {
                result = Objects.requireNonNull(operation.get(), "operation returned no result");
            } catch (RuntimeException e) {
                releaseClaim(keyHash, ownerToken);
                throw e;
            }

            // Provider 已经开始后，TECHNICAL_FAILURE 也可能是“远端写入成功，但响应超时”。
            // 三种 outcome 都必须落成 COMPLETED。需要重试时应先查询/人工核对，再创建新的操作 key。
            CapabilityResultCodec.EncodedCapabilityResult encoded = encodeDurableResult(result);
            long now = System.currentTimeMillis();
            int changedRows = databaseClient.update("""
                    UPDATE cs_capability_idempotency
                    SET status = ?, result_json = ?, result_schema_version = ?,
                        lease_until = 0, updated_at = ?
                    WHERE storage_key_hash = ?
                      AND request_fingerprint_hash = ?
                      AND status = ?
                      AND owner_token = ?
                    """,
                    COMPLETED,
                    encoded.json(),
                    encoded.schemaVersion(),
                    now,
                    keyHash,
                    fingerprintHash,
                    IN_PROGRESS,
                    ownerToken);
            if (changedRows == 1) {
                return result;
            }

            // 极端情况下当前 owner 仍可能因数据库长时间不可用或严重时钟漂移丢失租约。
            // 若新 owner 已完成，就以数据库结果为准；否则不能悄悄覆盖它的记录。
            Optional<StoredRecord> current = load(keyHash);
            if (current.isPresent()
                    && fingerprintHash.equals(current.get().requestFingerprintHash())
                    && COMPLETED.equals(current.get().status())) {
                return decodeReplay(current.get());
            }
            throw new IdempotencyExecutionInProgressException(keyHash);
        } finally {
            // 续租必须覆盖 Provider 执行、结果编码和 COMPLETED 落库整个临界区。
            leaseRenewal.cancel(false);
        }
    }

    private ScheduledFuture<?> startLeaseRenewal(String keyHash,
                                                 String fingerprintHash,
                                                 String ownerToken) {
        // 每经过约三分之一租约就续期一次。即使 Provider 比预期慢，只要当前实例仍健康且数据库
        // 可访问，其他实例就看不到过期租约，也不会在原调用尚未结束时接管。
        long renewalIntervalMs = Math.max(1, leaseMs / 3);
        return LEASE_RENEWAL_EXECUTOR.scheduleAtFixedRate(
                () -> renewLease(keyHash, fingerprintHash, ownerToken),
                renewalIntervalMs,
                renewalIntervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void renewLease(String keyHash, String fingerprintHash, String ownerToken) {
        long now = System.currentTimeMillis();
        try {
            databaseClient.update("""
                    UPDATE cs_capability_idempotency
                    SET lease_until = ?, updated_at = ?
                    WHERE storage_key_hash = ?
                      AND request_fingerprint_hash = ?
                      AND status = ?
                      AND owner_token = ?
                    """,
                    now + leaseMs,
                    now,
                    keyHash,
                    fingerprintHash,
                    IN_PROGRESS,
                    ownerToken);
        } catch (RuntimeException ignored) {
            // 续租线程不能因一次短暂数据库异常永久停止。主执行线程在保存结果时仍会看到真实错误；
            // 若数据库持续不可用，其他实例也无法成功接管，恢复后下一次心跳会继续尝试。
        }
    }

    private CapabilityResultCodec.EncodedCapabilityResult encodeDurableResult(CapabilityResult<?> result) {
        try {
            return resultCodec.encode(result);
        } catch (RuntimeException serializationFailure) {
            // 副作用可能已经成功，序列化失败时绝不能删除 claim 后让它再次执行。
            // 保存一个可稳定回放的技术结果，提醒上层人工核对，同时继续阻止重复调用。
            CapabilityResult<?> fallback = CapabilityResult.technicalFailure(
                    result.getCapabilityId(),
                    result.getProviderId(),
                    "IDEMPOTENCY_RESULT_SERIALIZATION_FAILED",
                    "Capability completed, but its replay result could not be serialized"
            );
            return resultCodec.encode(fallback);
        }
    }

    private void releaseClaim(String keyHash, String ownerToken) {
        databaseClient.update("""
                DELETE FROM cs_capability_idempotency
                WHERE storage_key_hash = ? AND status = ? AND owner_token = ?
                """,
                keyHash,
                IN_PROGRESS,
                ownerToken);
    }

    private Optional<StoredRecord> load(String keyHash) {
        return databaseClient.queryOne("""
                        SELECT request_fingerprint_hash, status, owner_token, lease_until,
                               result_json, result_schema_version
                        FROM cs_capability_idempotency
                        WHERE storage_key_hash = ?
                        """,
                resultSet -> new StoredRecord(
                        resultSet.getString("request_fingerprint_hash"),
                        resultSet.getString("status"),
                        resultSet.getString("owner_token"),
                        resultSet.getLong("lease_until"),
                        resultSet.getString("result_json"),
                        resultSet.getInt("result_schema_version")
                ),
                keyHash);
    }

    private CapabilityResult<?> decodeReplay(StoredRecord record) {
        if (record.resultJson() == null || record.resultSchemaVersion() <= 0) {
            throw new IllegalStateException("Completed idempotency record has no replay result");
        }
        return resultCodec.decode(record.resultJson(), record.resultSchemaVersion()).asReplayed();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for idempotent result", e);
        }
    }

    private void throwWaitLimit(String storageKey,
                                long requestWaitDeadline,
                                long configuredWaitDeadline) {
        if (requestWaitDeadline <= configuredWaitDeadline) {
            throw new IdempotencyWaitDeadlineExceededException(storageKey);
        }
        throw new IdempotencyExecutionInProgressException(storageKey);
    }

    private long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private long epochMilliOrBoundary(Instant instant) {
        try {
            return instant.toEpochMilli();
        } catch (ArithmeticException outOfEpochRange) {
            // 外部 Store 调用者可能直接传 Instant.MAX/MIN；把它们钳制到 long 边界，避免时间换算异常
            // 被 Gateway 误报为幂等数据库故障。正常 Capability deadline 都远小于这个范围。
            return instant.isAfter(Instant.EPOCH) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "capability-idempotency-lease-renewal");
            thread.setDaemon(true);
            return thread;
        };
    }

    private record StoredRecord(
            String requestFingerprintHash,
            String status,
            String ownerToken,
            long leaseUntil,
            String resultJson,
            int resultSchemaVersion
    ) {
    }
}
