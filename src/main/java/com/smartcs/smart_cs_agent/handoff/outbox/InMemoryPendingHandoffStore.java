package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地演示和单元测试使用的 Outbox。
 *
 * <p>它能保证单 JVM 内并发去重，但不能跨进程恢复；生产 JDBC 模式会自动切换到数据库实现。</p>
 */
@Component
@ConditionalOnProperty(
        name = "smartcs.persistence.mode",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemoryPendingHandoffStore implements PendingHandoffStore {

    /*
     * Map 的 key 必须同时包含 tenantId 和 idempotencyKey。若仍使用 String，租户 A 的成功结果或
     * 短租约可能被租户 B 的同名幂等请求误用。
     */
    private final Map<PendingHandoffKey, StoredEntry> records = new ConcurrentHashMap<>();
    private final Map<PendingHandoffKey, ClaimEntry> claims = new ConcurrentHashMap<>();
    private final long preparedGraceMs;
    private static final Set<PendingHandoffStatus> DUE_STATUSES = Set.of(
            PendingHandoffStatus.PREPARED,
            PendingHandoffStatus.RESULT_UNKNOWN,
            PendingHandoffStatus.RETRY_SCHEDULED,
            PendingHandoffStatus.PROCESSING
    );

    /** 手动构造和单元测试使用与生产默认值一致的 30 秒宽限期。 */
    public InMemoryPendingHandoffStore() {
        this(30_000);
    }

    @Autowired
    public InMemoryPendingHandoffStore(
            @Value("${smartcs.handoff.recovery.prepared-grace-ms:30000}") long preparedGraceMs) {
        if (preparedGraceMs <= 0) {
            throw new IllegalArgumentException("preparedGraceMs must be positive");
        }
        this.preparedGraceMs = preparedGraceMs;
    }

    @Override
    public PendingHandoffPreparation prepare(PendingHandoffCommand command) {
        PendingHandoffKey key = command.key();
        String fingerprint = PendingHandoffFingerprint.of(command);
        StoredEntry candidate = new StoredEntry(fingerprint, newRecord(command));
        StoredEntry existing = records.putIfAbsent(key, candidate);
        if (existing == null) {
            return new PendingHandoffPreparation(candidate.record(), true);
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new PendingHandoffConflictException();
        }
        return new PendingHandoffPreparation(existing.record(), false);
    }

    @Override
    public PendingHandoffRecord markResultUnknown(PendingHandoffKey key,
                                                  String failureType,
                                                  long expectedVersion) {
        StoredEntry existing = requireEntry(key);
        if (existing.record().status() == PendingHandoffStatus.CONFIRMED) {
            // 成功是不可逆终态。迟到的超时线程不能把已确认结果改回未知。
            return existing.record();
        }
        return replace(key, expectedVersion, current -> new PendingHandoffRecord(
                current.outboxId(),
                current.tenantId(),
                current.userId(),
                current.conversationId(),
                current.reason(),
                current.summary(),
                current.request(),
                PendingHandoffStatus.RESULT_UNKNOWN,
                safeFailureType(failureType),
                null,
                current.attemptCount() + 1,
                System.currentTimeMillis(),
                current.version() + 1,
                current.createdAt(),
                System.currentTimeMillis()
        ));
    }

    @Override
    public PendingHandoffRecord confirm(PendingHandoffKey key,
                                        AfterSalesTicketCreateResult result,
                                        long expectedVersion) {
        if (result == null) {
            throw new IllegalArgumentException("result cannot be null");
        }
        StoredEntry existing = requireEntry(key);
        if (existing.record().status() == PendingHandoffStatus.CONFIRMED) {
            if (!java.util.Objects.equals(existing.record().confirmedResult(), result)) {
                throw new PendingHandoffConflictException();
            }
            return existing.record();
        }
        return replace(key, expectedVersion, current -> new PendingHandoffRecord(
                current.outboxId(),
                current.tenantId(),
                current.userId(),
                current.conversationId(),
                current.reason(),
                current.summary(),
                current.request(),
                PendingHandoffStatus.CONFIRMED,
                "NONE",
                result,
                current.attemptCount() + 1,
                0,
                current.version() + 1,
                current.createdAt(),
                System.currentTimeMillis()
        ));
    }

    @Override
    public List<PendingHandoffRecord> findDue(long now, int limit) {
        requirePositive(limit, "limit");
        return records.values().stream()
                .map(StoredEntry::record)
                .filter(record -> DUE_STATUSES.contains(record.status()))
                .filter(record -> record.nextAttemptAt() <= now)
                .sorted(Comparator.comparingLong(PendingHandoffRecord::nextAttemptAt)
                        .thenComparing(PendingHandoffRecord::outboxId))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<PendingHandoffClaim> tryClaim(PendingHandoffKey key,
                                                  long expectedVersion,
                                                  String ownerToken,
                                                  long now,
                                                  long leaseMs) {
        requireText(ownerToken, "ownerToken");
        if (leaseMs <= 0) {
            throw new IllegalArgumentException("leaseMs must be positive");
        }
        StoredEntry current = records.get(key);
        if (current == null) {
            // 与 JDBC 实现保持一致：扫描结果可能已经被其他实例删除或归档，领取时返回 empty 即可。
            return Optional.empty();
        }
        if (current.record().version() != expectedVersion
                || !DUE_STATUSES.contains(current.record().status())
                || current.record().nextAttemptAt() > now) {
            return Optional.empty();
        }

        long leaseUntil = now + leaseMs;
        AtomicBoolean acquired = new AtomicBoolean(false);
        claims.compute(key, (ignored, existing) -> {
            if (existing == null || existing.leaseUntil() <= now) {
                acquired.set(true);
                return new ClaimEntry(ownerToken, leaseUntil);
            }
            return existing;
        });
        if (!acquired.get()) {
            return Optional.empty();
        }

        try {
            PendingHandoffRecord processing = replace(
                    key,
                    expectedVersion,
                    record -> copyWithState(
                            record,
                            PendingHandoffStatus.PROCESSING,
                            record.failureType(),
                            record.confirmedResult(),
                            record.attemptCount(),
                            leaseUntil
                    )
            );
            return Optional.of(new PendingHandoffClaim(processing, ownerToken, leaseUntil));
        } catch (PendingHandoffVersionConflictException conflict) {
            claims.remove(key, new ClaimEntry(ownerToken, leaseUntil));
            return Optional.empty();
        }
    }

    @Override
    public PendingHandoffRecord scheduleRetry(PendingHandoffClaim claim,
                                              String failureType,
                                              long nextAttemptAt) {
        requireClaimOwner(claim);
        try {
            return replace(
                    claim.record().key(),
                    claim.record().version(),
                    record -> copyWithState(
                            record,
                            PendingHandoffStatus.RETRY_SCHEDULED,
                            safeFailureType(failureType),
                            null,
                            record.attemptCount() + 1,
                            nextAttemptAt
                    )
            );
        } finally {
            releaseClaim(claim);
        }
    }

    @Override
    public PendingHandoffRecord markNeedsManualReview(PendingHandoffClaim claim,
                                                      String failureType) {
        requireClaimOwner(claim);
        try {
            return replace(
                    claim.record().key(),
                    claim.record().version(),
                    record -> copyWithState(
                            record,
                            PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                            safeFailureType(failureType),
                            null,
                            record.attemptCount() + 1,
                            Long.MAX_VALUE
                    )
            );
        } finally {
            releaseClaim(claim);
        }
    }

    @Override
    public void releaseClaim(PendingHandoffClaim claim) {
        if (claim == null) {
            return;
        }
        PendingHandoffKey key = claim.record().key();
        claims.computeIfPresent(key, (ignored, existing) ->
                existing.ownerToken().equals(claim.ownerToken()) ? null : existing
        );
    }

    @Override
    public Optional<PendingHandoffRecord> findByKey(PendingHandoffKey key) {
        StoredEntry entry = records.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.record());
    }

    public int size() {
        return records.size();
    }

    private PendingHandoffRecord newRecord(PendingHandoffCommand command) {
        long now = System.currentTimeMillis();
        return new PendingHandoffRecord(
                UUID.randomUUID().toString(),
                command.tenantId(),
                command.userId(),
                command.conversationId(),
                command.reason(),
                command.summary(),
                command.request(),
                PendingHandoffStatus.PREPARED,
                "NONE",
                null,
                0,
                safeAdd(now, preparedGraceMs),
                1,
                now,
                now
        );
    }

    private PendingHandoffRecord replace(PendingHandoffKey key,
                                         long expectedVersion,
                                         java.util.function.Function<PendingHandoffRecord, PendingHandoffRecord> updater) {
        StoredEntry existing = requireEntry(key);
        if (existing.record().version() != expectedVersion) {
            throw new PendingHandoffVersionConflictException();
        }
        PendingHandoffRecord updatedRecord = updater.apply(existing.record());
        StoredEntry updated = new StoredEntry(existing.fingerprint(), updatedRecord);
        if (!records.replace(key, existing, updated)) {
            throw new PendingHandoffVersionConflictException();
        }
        return updatedRecord;
    }

    private PendingHandoffRecord copyWithState(PendingHandoffRecord current,
                                               PendingHandoffStatus status,
                                               String failureType,
                                               AfterSalesTicketCreateResult result,
                                               int attemptCount,
                                               long nextAttemptAt) {
        return new PendingHandoffRecord(
                current.outboxId(),
                current.tenantId(),
                current.userId(),
                current.conversationId(),
                current.reason(),
                current.summary(),
                current.request(),
                status,
                failureType,
                result,
                attemptCount,
                nextAttemptAt,
                current.version() + 1,
                current.createdAt(),
                System.currentTimeMillis()
        );
    }

    private void requireClaimOwner(PendingHandoffClaim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("claim cannot be null");
        }
        PendingHandoffKey key = claim.record().key();
        ClaimEntry existing = claims.get(key);
        if (existing == null || !existing.ownerToken().equals(claim.ownerToken())) {
            throw new PendingHandoffVersionConflictException();
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }

    private void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private StoredEntry requireEntry(PendingHandoffKey key) {
        StoredEntry entry = records.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Pending handoff record does not exist");
        }
        return entry;
    }

    private String safeFailureType(String failureType) {
        if (failureType == null || failureType.isBlank()) {
            return "UNKNOWN";
        }
        String compact = failureType.replaceAll("[^A-Za-z0-9_$.-]", "_");
        return compact.length() <= 128 ? compact : compact.substring(0, 128);
    }

    private record StoredEntry(String fingerprint, PendingHandoffRecord record) {
    }

    private record ClaimEntry(String ownerToken, long leaseUntil) {
    }
}
