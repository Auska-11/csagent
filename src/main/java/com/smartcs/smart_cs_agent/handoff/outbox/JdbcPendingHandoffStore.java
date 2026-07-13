package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewNotFoundException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewStore;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseOperations;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MySQL 人工转接 Outbox。
 *
 * <p>复合唯一键保证同一租户的相同外部 idempotencyKey 只能保存一条记录；请求指纹保证 key 被
 * 错误复用于不同参数时立即失败。表中保存完整远端请求，是因为进程重启后后台任务仍需要用相同
 * 参数、租户和 key 核对或补建。</p>
 */
@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
public class JdbcPendingHandoffStore implements PendingHandoffStore, PendingHandoffReviewStore {

    private static final Set<PendingHandoffStatus> DUE_STATUSES = Set.of(
            PendingHandoffStatus.PREPARED,
            PendingHandoffStatus.RESULT_UNKNOWN,
            PendingHandoffStatus.RETRY_SCHEDULED,
            PendingHandoffStatus.PROCESSING
    );

    private final JdbcDatabaseClient databaseClient;
    private final long preparedGraceMs;

    public JdbcPendingHandoffStore(JdbcDatabaseClient databaseClient) {
        this(databaseClient, 30_000);
    }

    @Autowired
    public JdbcPendingHandoffStore(
            JdbcDatabaseClient databaseClient,
            @Value("${smartcs.handoff.recovery.prepared-grace-ms:30000}") long preparedGraceMs) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient cannot be null");
        if (preparedGraceMs <= 0) {
            throw new IllegalArgumentException("preparedGraceMs must be positive");
        }
        this.preparedGraceMs = preparedGraceMs;
        initTable();
    }

    private void initTable() {
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_pending_handoff (
                    outbox_id CHAR(36) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    request_fingerprint_hash CHAR(64) NOT NULL,
                    user_id VARCHAR(64) NOT NULL,
                    conversation_id VARCHAR(128) NOT NULL,
                    reason VARCHAR(64) NOT NULL,
                    summary_text TEXT NOT NULL,
                    request_title VARCHAR(120) NOT NULL,
                    request_description TEXT NOT NULL,
                    customer_id VARCHAR(64) NOT NULL,
                    product_line VARCHAR(64) NOT NULL,
                    issue_type VARCHAR(64) NOT NULL,
                    priority_code VARCHAR(32) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    failure_type VARCHAR(128) NOT NULL,
                    attempt_count INT NOT NULL,
                    next_attempt_at BIGINT NOT NULL,
                    version BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (outbox_id),
                    UNIQUE KEY uk_cs_pending_handoff_tenant_idempotency (tenant_id, idempotency_key),
                    INDEX idx_cs_pending_handoff_due (status, next_attempt_at),
                    INDEX idx_cs_pending_handoff_tenant_review
                        (tenant_id, status, updated_at, outbox_id)
                )
                """);
        // 成功结果使用独立的追加式表保存，避免 CREATE TABLE IF NOT EXISTS 无法升级上一批已创建的主表。
        // 结果行一旦存在就是远端成功的证据；即使更新主表状态时进程退出，重启后仍能从这里回放。
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_pending_handoff_result (
                    tenant_id VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    ticket_id VARCHAR(128) NOT NULL,
                    ticket_status VARCHAR(64) NOT NULL,
                    ticket_created_at VARCHAR(128) NOT NULL,
                    recorded_at BIGINT NOT NULL,
                    PRIMARY KEY (tenant_id, idempotency_key)
                )
                """);
        // 领取租约独立成表，避免把短生命周期 owner 信息混入长期业务记录。
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_pending_handoff_claim (
                    tenant_id VARCHAR(64) NOT NULL,
                    idempotency_key VARCHAR(255) NOT NULL,
                    owner_token VARCHAR(64) NOT NULL,
                    lease_until BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (tenant_id, idempotency_key),
                    INDEX idx_cs_pending_handoff_claim_lease (lease_until)
                )
                """);
    }

    @Override
    public PendingHandoffPreparation prepare(PendingHandoffCommand command) {
        String fingerprint = PendingHandoffFingerprint.of(command);
        long now = System.currentTimeMillis();
        AfterSalesTicketCreateRequest request = command.request();
        String outboxId = UUID.randomUUID().toString();
        try {
            int changed = databaseClient.update("""
                    INSERT INTO cs_pending_handoff(
                        outbox_id, tenant_id, idempotency_key, request_fingerprint_hash,
                        user_id, conversation_id, reason, summary_text,
                        request_title, request_description, customer_id,
                        product_line, issue_type, priority_code,
                        status, failure_type, attempt_count, next_attempt_at,
                        version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    outboxId,
                    command.tenantId(),
                    request.idempotencyKey(),
                    fingerprint,
                    command.userId(),
                    command.conversationId(),
                    command.reason(),
                    command.summary(),
                    request.title(),
                    request.description(),
                    request.customerId(),
                    request.productLine(),
                    request.issueType(),
                    request.priority(),
                    PendingHandoffStatus.PREPARED.name(),
                    "NONE",
                    0,
                    safeAdd(now, preparedGraceMs),
                    1,
                    now,
                    now
            );
            if (changed != 1) {
                throw new IllegalStateException("Pending handoff insert did not change exactly one row");
            }
        } catch (JdbcDuplicateKeyException duplicateKey) {
            // 并发请求可能同时插入同一个 key。唯一键决定胜者，失败者读取既有记录并校验指纹。
            StoredPendingHandoff existing = loadStored(command.key())
                    .orElseThrow(() -> duplicateKey);
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new PendingHandoffConflictException();
            }
            return new PendingHandoffPreparation(existing.record(), false);
        }
        PendingHandoffRecord inserted = findByKey(command.key())
                .orElseThrow(() -> new IllegalStateException("Inserted pending handoff could not be loaded"));
        return new PendingHandoffPreparation(inserted, true);
    }

    @Override
    public PendingHandoffRecord markResultUnknown(PendingHandoffKey key,
                                                  String failureType,
                                                  long expectedVersion) {
        Objects.requireNonNull(key, "key cannot be null");
        long now = System.currentTimeMillis();
        int changed = databaseClient.update("""
                UPDATE cs_pending_handoff
                SET status = ?, failure_type = ?, attempt_count = attempt_count + 1,
                    next_attempt_at = ?, version = version + 1, updated_at = ?
                WHERE tenant_id = ?
                  AND idempotency_key = ?
                  AND version = ?
                  AND status IN (?, ?)
                """,
                PendingHandoffStatus.RESULT_UNKNOWN.name(),
                safeFailureType(failureType),
                now,
                now,
                key.tenantId(),
                key.idempotencyKey(),
                expectedVersion,
                PendingHandoffStatus.PREPARED.name(),
                PendingHandoffStatus.RESULT_UNKNOWN.name());
        if (changed == 1) {
            return findByKey(key)
                    .orElseThrow(() -> new IllegalStateException("Updated pending handoff could not be loaded"));
        }
        PendingHandoffRecord current = findByKey(key)
                .orElseThrow(() -> new IllegalArgumentException("Pending handoff record does not exist"));
        if (current.status() == PendingHandoffStatus.CONFIRMED) {
            // 另一个核对执行者已经取得真实成功结果。成功证据优先于当前线程的网络异常。
            return current;
        }
        if (current.version() == expectedVersion
                && current.status() != PendingHandoffStatus.PREPARED
                && current.status() != PendingHandoffStatus.RESULT_UNKNOWN) {
            throw new PendingHandoffStateConflictException(current.status());
        }
        throw new PendingHandoffVersionConflictException();
    }

    @Override
    public PendingHandoffRecord confirm(PendingHandoffKey key,
                                        AfterSalesTicketCreateResult result,
                                        long expectedVersion) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(result, "result cannot be null");
        PendingHandoffRecord locked = databaseClient.inTransaction(operations -> {
            /*
             * 先锁主记录，再检查 MANUALLY_CLOSED 并保存结果证据。人工 close 也会锁同一行，
             * 因此两者只能有一个先完成：
             * 1. close 先完成，迟到 Worker 看到 CLOSED 后不能插入结果；
             * 2. confirm 先提交结果，close 回读时会看到有效 CONFIRMED 并拒绝关闭。
             *
             * 这里只把“终态围栏 + 结果证据”放进事务，主表状态投影仍在事务提交后执行。
             * 这样进程即使在两步之间退出，追加式结果表仍能在重启后证明远端已经成功。
             */
            PendingHandoffRecord current = findByKeyForUpdate(operations, key)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Pending handoff record does not exist"
                    ));
            if (current.status() == PendingHandoffStatus.CONFIRMED) {
                ensureSameResult(current.confirmedResult(), result);
                return current;
            }
            if (current.status() == PendingHandoffStatus.MANUALLY_CLOSED) {
                throw new PendingHandoffStateConflictException(current.status());
            }
            if (current.version() != expectedVersion) {
                throw new PendingHandoffVersionConflictException();
            }
            saveConfirmedResult(operations, key, result);
            return current;
        });
        if (locked.status() == PendingHandoffStatus.CONFIRMED) {
            return locked;
        }

        // 成功结果表已经成为权威证据，所以这里允许将任意非终态推进为 CONFIRMED。
        // 如果进程在结果事务提交后、UPDATE 主表前退出，loadStored 也会根据结果行恢复成 CONFIRMED。
        // MANUALLY_CLOSED 是唯一优先级更高的人工作业终态，SQL 必须显式排除，不能被迟到 Worker 复活。
        long now = System.currentTimeMillis();
        int changed = databaseClient.update("""
                UPDATE cs_pending_handoff
                SET status = ?, failure_type = ?, attempt_count = attempt_count + 1,
                    next_attempt_at = 0, version = version + 1, updated_at = ?
                WHERE tenant_id = ? AND idempotency_key = ?
                  AND status NOT IN (?, ?)
                """,
                PendingHandoffStatus.CONFIRMED.name(),
                "NONE",
                now,
                key.tenantId(),
                key.idempotencyKey(),
                PendingHandoffStatus.CONFIRMED.name(),
                PendingHandoffStatus.MANUALLY_CLOSED.name());

        PendingHandoffRecord confirmed = findByKey(key)
                .orElseThrow(() -> new IllegalStateException("Confirmed pending handoff could not be loaded"));
        if (confirmed.status() == PendingHandoffStatus.MANUALLY_CLOSED) {
            // 对历史异常留下的 MANUALLY_CLOSED + result 脏组合也保持 fail-closed，绝不伪装成成功。
            throw new PendingHandoffStateConflictException(confirmed.status());
        }
        ensureSameResult(confirmed.confirmedResult(), result);
        if (changed == 0 && confirmed.status() != PendingHandoffStatus.CONFIRMED) {
            throw new PendingHandoffVersionConflictException();
        }
        return confirmed;
    }

    @Override
    public List<PendingHandoffRecord> findDue(long now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<PendingHandoffKey> keys = databaseClient.queryList("""
                        SELECT tenant_id, idempotency_key
                        FROM cs_pending_handoff
                        WHERE status IN (?, ?, ?, ?)
                          AND next_attempt_at <= ?
                          AND NOT EXISTS (
                              SELECT 1 FROM cs_pending_handoff_result AS result_row
                              WHERE result_row.tenant_id = cs_pending_handoff.tenant_id
                                AND result_row.idempotency_key = cs_pending_handoff.idempotency_key
                          )
                        ORDER BY next_attempt_at, outbox_id
                        LIMIT ?
                        """,
                resultSet -> new PendingHandoffKey(
                        resultSet.getString("tenant_id"),
                        resultSet.getString("idempotency_key")
                ),
                PendingHandoffStatus.PREPARED.name(),
                PendingHandoffStatus.RESULT_UNKNOWN.name(),
                PendingHandoffStatus.RETRY_SCHEDULED.name(),
                PendingHandoffStatus.PROCESSING.name(),
                now,
                limit);
        return keys.stream()
                .map(this::findByKey)
                .flatMap(Optional::stream)
                .filter(record -> record.status() != PendingHandoffStatus.CONFIRMED)
                .toList();
    }

    @Override
    public Optional<PendingHandoffClaim> tryClaim(PendingHandoffKey key,
                                                  long expectedVersion,
                                                  String ownerToken,
                                                  long now,
                                                  long leaseMs) {
        Objects.requireNonNull(key, "key cannot be null");
        requireText(ownerToken, "ownerToken");
        if (leaseMs <= 0) {
            throw new IllegalArgumentException("leaseMs must be positive");
        }
        PendingHandoffRecord current = findByKey(key).orElse(null);
        if (current == null
                || current.version() != expectedVersion
                || !DUE_STATUSES.contains(current.status())
                || current.nextAttemptAt() > now) {
            return Optional.empty();
        }

        long leaseUntil = now + leaseMs;
        if (!acquireClaimRow(key, ownerToken, now, leaseUntil)) {
            return Optional.empty();
        }

        int changed = databaseClient.update("""
                UPDATE cs_pending_handoff
                SET status = ?, next_attempt_at = ?, version = version + 1, updated_at = ?
                WHERE tenant_id = ?
                  AND idempotency_key = ?
                  AND version = ?
                  AND status IN (?, ?, ?, ?)
                  AND next_attempt_at <= ?
                """,
                PendingHandoffStatus.PROCESSING.name(),
                leaseUntil,
                now,
                key.tenantId(),
                key.idempotencyKey(),
                expectedVersion,
                PendingHandoffStatus.PREPARED.name(),
                PendingHandoffStatus.RESULT_UNKNOWN.name(),
                PendingHandoffStatus.RETRY_SCHEDULED.name(),
                PendingHandoffStatus.PROCESSING.name(),
                now);
        if (changed != 1) {
            releaseClaimRow(key, ownerToken);
            return Optional.empty();
        }
        PendingHandoffRecord processing = findByKey(key)
                .orElseThrow(() -> new IllegalStateException("Claimed pending handoff could not be loaded"));
        return Optional.of(new PendingHandoffClaim(processing, ownerToken, leaseUntil));
    }

    @Override
    public PendingHandoffRecord scheduleRetry(PendingHandoffClaim claim,
                                              String failureType,
                                              long nextAttemptAt) {
        return finishClaim(
                claim,
                PendingHandoffStatus.RETRY_SCHEDULED,
                failureType,
                nextAttemptAt
        );
    }

    @Override
    public PendingHandoffRecord markNeedsManualReview(PendingHandoffClaim claim,
                                                      String failureType) {
        return finishClaim(
                claim,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                failureType,
                Long.MAX_VALUE
        );
    }

    @Override
    public void releaseClaim(PendingHandoffClaim claim) {
        if (claim != null) {
            releaseClaimRow(claim.record().key(), claim.ownerToken());
        }
    }

    @Override
    public Optional<PendingHandoffRecord> findByKey(PendingHandoffKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        return loadStored(key).map(StoredPendingHandoff::record);
    }

    @Override
    public PendingHandoffReviewPage findReviewPage(String tenantId,
                                                   PendingHandoffStatus status,
                                                   PendingHandoffReviewCursor cursor,
                                                   int limit) {
        requireText(tenantId, "tenantId");
        Objects.requireNonNull(status, "status cannot be null");
        requireReviewLimit(limit);
        String normalizedTenant = tenantId.trim();
        return databaseClient.inTransaction(operations -> {
            // key 查询和逐条结果投影必须共享一个 RR 快照；否则分页集合可能在 N+1 读取间漂移。
            int fetchLimit = limit + 1;
            String effectiveStatusPredicate = reviewStatusPredicate(status);
            String cursorPredicate = cursor == null
                    ? ""
                    : "AND (updated_at < ? OR (updated_at = ? AND outbox_id < ?))";
            String pageSql = """
                    SELECT tenant_id, idempotency_key
                    FROM cs_pending_handoff
                    WHERE tenant_id = ? AND %s
                    %s
                    ORDER BY updated_at DESC, outbox_id DESC
                    LIMIT ?
                    """.formatted(effectiveStatusPredicate, cursorPredicate);
            List<PendingHandoffKey> keys = cursor == null
                    ? operations.queryList(
                            pageSql,
                            this::mapKey,
                            normalizedTenant,
                            status.name(),
                            fetchLimit
                    )
                    : operations.queryList(
                            pageSql,
                            this::mapKey,
                            normalizedTenant,
                            status.name(),
                            cursor.updatedAt(),
                            cursor.updatedAt(),
                            cursor.outboxId(),
                            fetchLimit
                    );

            List<PendingHandoffRecord> candidates = keys.stream()
                    .map(key -> loadStored(operations, key).map(StoredPendingHandoff::record))
                    .flatMap(Optional::stream)
                    .toList();
            boolean hasMore = candidates.size() > limit;
            List<PendingHandoffRecord> pageRecords = hasMore
                    ? List.copyOf(candidates.subList(0, limit))
                    : candidates;
            PendingHandoffReviewCursor nextCursor = hasMore
                    ? cursorOf(pageRecords.get(pageRecords.size() - 1))
                    : null;
            return new PendingHandoffReviewPage(pageRecords, nextCursor, hasMore);
        });
    }

    @Override
    public Optional<PendingHandoffRecord> findByTenantAndOutboxId(String tenantId, String outboxId) {
        requireText(tenantId, "tenantId");
        requireText(outboxId, "outboxId");
        return findByTenantAndOutboxId(
                databaseClient,
                tenantId.trim(),
                outboxId.trim()
        );
    }

    @Override
    public PendingHandoffRecord retryFromManualReview(String tenantId,
                                                      String outboxId,
                                                      long expectedVersion,
                                                      long scheduledAt) {
        requireTimestamp(scheduledAt, "scheduledAt");
        return updateManualReviewState(
                tenantId,
                outboxId,
                expectedVersion,
                PendingHandoffStatus.RETRY_SCHEDULED,
                "MANUAL_RETRY_REQUESTED",
                scheduledAt,
                scheduledAt
        );
    }

    @Override
    public PendingHandoffRecord confirmFromManualReview(String tenantId,
                                                        String outboxId,
                                                        AfterSalesTicketCreateResult result,
                                                        long expectedVersion) {
        requireText(tenantId, "tenantId");
        requireText(outboxId, "outboxId");
        Objects.requireNonNull(result, "result cannot be null");
        String normalizedTenant = tenantId.trim();
        String normalizedOutboxId = outboxId.trim();

        return databaseClient.inTransaction(operations -> {
            // 事务回调中只能使用 operations，不能调用外层 databaseClient，否则会意外打开新连接。
            PendingHandoffRecord current = findByTenantAndOutboxIdForUpdate(
                    operations,
                    normalizedTenant,
                    normalizedOutboxId
            ).orElseThrow(PendingHandoffReviewNotFoundException::new);
            validateManualReviewTransition(current, expectedVersion);

            saveConfirmedResult(operations, current.key(), result);
            long now = System.currentTimeMillis();
            int changed = operations.update("""
                    UPDATE cs_pending_handoff
                    SET status = ?, failure_type = ?, next_attempt_at = 0,
                        version = version + 1, updated_at = ?
                    WHERE tenant_id = ? AND outbox_id = ?
                      AND version = ? AND status = ?
                    """,
                    PendingHandoffStatus.CONFIRMED.name(),
                    "NONE",
                    now,
                    normalizedTenant,
                    normalizedOutboxId,
                    expectedVersion,
                    PendingHandoffStatus.NEEDS_MANUAL_REVIEW.name());
            if (changed != 1) {
                // 结果 INSERT 也在同一事务内；抛异常会统一 rollback，不会留下半成功证据。
                throw new PendingHandoffVersionConflictException();
            }
            return loadStored(operations, current.key())
                    .map(StoredPendingHandoff::record)
                    .orElseThrow(() -> new IllegalStateException(
                            "Confirmed manual handoff could not be loaded"
                    ));
        });
    }

    @Override
    public PendingHandoffRecord closeFromManualReview(String tenantId,
                                                      String outboxId,
                                                      long expectedVersion,
                                                      long closedAt) {
        requireTimestamp(closedAt, "closedAt");
        return updateManualReviewState(
                tenantId,
                outboxId,
                expectedVersion,
                PendingHandoffStatus.MANUALLY_CLOSED,
                "MANUALLY_CLOSED",
                Long.MAX_VALUE,
                closedAt
        );
    }

    private Optional<StoredPendingHandoff> loadStored(PendingHandoffKey key) {
        return loadStored(databaseClient, key);
    }

    private Optional<StoredPendingHandoff> loadStored(JdbcDatabaseOperations operations,
                                                       PendingHandoffKey key) {
        Optional<StoredPendingHandoff> base = operations.queryOne("""
                        SELECT outbox_id, tenant_id, idempotency_key, request_fingerprint_hash,
                               user_id, conversation_id, reason, summary_text,
                               request_title, request_description, customer_id,
                               product_line, issue_type, priority_code,
                               status, failure_type, attempt_count, next_attempt_at,
                               version, created_at, updated_at
                        FROM cs_pending_handoff
                        WHERE tenant_id = ? AND idempotency_key = ?
                        """,
                resultSet -> {
                    AfterSalesTicketCreateRequest request = new AfterSalesTicketCreateRequest(
                            resultSet.getString("request_title"),
                            resultSet.getString("request_description"),
                            resultSet.getString("customer_id"),
                            resultSet.getString("product_line"),
                            resultSet.getString("issue_type"),
                            resultSet.getString("priority_code"),
                            resultSet.getString("idempotency_key")
                    );
                    PendingHandoffRecord record = new PendingHandoffRecord(
                            resultSet.getString("outbox_id"),
                            resultSet.getString("tenant_id"),
                            resultSet.getString("user_id"),
                            resultSet.getString("conversation_id"),
                            resultSet.getString("reason"),
                            resultSet.getString("summary_text"),
                            request,
                            PendingHandoffStatus.valueOf(resultSet.getString("status")),
                            resultSet.getString("failure_type"),
                            null,
                            resultSet.getInt("attempt_count"),
                            resultSet.getLong("next_attempt_at"),
                            resultSet.getLong("version"),
                            resultSet.getLong("created_at"),
                            resultSet.getLong("updated_at")
                    );
                    return new StoredPendingHandoff(
                            resultSet.getString("request_fingerprint_hash"),
                            record
                    );
                },
                key.tenantId(),
                key.idempotencyKey());
        if (base.isEmpty()) {
            return Optional.empty();
        }

        StoredPendingHandoff stored = base.get();
        PendingHandoffRecord current = stored.record();
        if (current.status() == PendingHandoffStatus.MANUALLY_CLOSED) {
            /*
             * 人工关闭是业务人员明确设置的终态，优先级高于历史异常可能残留的结果行。
             * 新代码通过行锁阻止这种组合继续产生；这里仍保留读取围栏，以安全兼容历史脏数据。
             */
            return base;
        }

        Optional<AfterSalesTicketCreateResult> confirmedResult = loadConfirmedResult(operations, key);
        if (confirmedResult.isEmpty()) {
            return base;
        }
        PendingHandoffRecord confirmed = new PendingHandoffRecord(
                current.outboxId(),
                current.tenantId(),
                current.userId(),
                current.conversationId(),
                current.reason(),
                current.summary(),
                current.request(),
                PendingHandoffStatus.CONFIRMED,
                "NONE",
                confirmedResult.get(),
                current.attemptCount(),
                0,
                current.version(),
                current.createdAt(),
                current.updatedAt()
        );
        return Optional.of(new StoredPendingHandoff(stored.fingerprint(), confirmed));
    }

    private void saveConfirmedResult(JdbcDatabaseOperations operations,
                                     PendingHandoffKey key,
                                     AfterSalesTicketCreateResult result) {
        try {
            operations.update("""
                    INSERT INTO cs_pending_handoff_result(
                        tenant_id, idempotency_key, ticket_id, ticket_status, ticket_created_at, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    key.tenantId(),
                    key.idempotencyKey(),
                    result.ticketId(),
                    result.status(),
                    result.createdAt(),
                    System.currentTimeMillis());
        } catch (JdbcDuplicateKeyException duplicateKey) {
            AfterSalesTicketCreateResult existing = loadConfirmedResult(operations, key)
                    .orElseThrow(() -> duplicateKey);
            ensureSameResult(existing, result);
        }
    }

    private Optional<AfterSalesTicketCreateResult> loadConfirmedResult(
            JdbcDatabaseOperations operations,
            PendingHandoffKey key) {
        return operations.queryOne("""
                        SELECT ticket_id, ticket_status, ticket_created_at
                        FROM cs_pending_handoff_result
                        WHERE tenant_id = ? AND idempotency_key = ?
                        """,
                resultSet -> new AfterSalesTicketCreateResult(
                        resultSet.getString("ticket_id"),
                        resultSet.getString("ticket_status"),
                        resultSet.getString("ticket_created_at")
                ),
                key.tenantId(),
                key.idempotencyKey());
    }

    private boolean acquireClaimRow(PendingHandoffKey key,
                                    String ownerToken,
                                    long now,
                                    long leaseUntil) {
        try {
            return databaseClient.update("""
                    INSERT INTO cs_pending_handoff_claim(
                        tenant_id, idempotency_key, owner_token, lease_until, updated_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    key.tenantId(),
                    key.idempotencyKey(),
                    ownerToken,
                    leaseUntil,
                    now) == 1;
        } catch (JdbcDuplicateKeyException duplicateKey) {
            Optional<StoredClaim> existing = loadClaim(key);
            if (existing.isEmpty() || existing.get().leaseUntil() > now) {
                return false;
            }
            StoredClaim expired = existing.get();
            return databaseClient.update("""
                    UPDATE cs_pending_handoff_claim
                    SET owner_token = ?, lease_until = ?, updated_at = ?
                    WHERE tenant_id = ?
                      AND idempotency_key = ?
                      AND owner_token = ?
                      AND lease_until = ?
                    """,
                    ownerToken,
                    leaseUntil,
                    now,
                    key.tenantId(),
                    key.idempotencyKey(),
                    expired.ownerToken(),
                    expired.leaseUntil()) == 1;
        }
    }

    private PendingHandoffRecord finishClaim(PendingHandoffClaim claim,
                                             PendingHandoffStatus targetStatus,
                                             String failureType,
                                             long nextAttemptAt) {
        Objects.requireNonNull(claim, "claim cannot be null");
        PendingHandoffKey key = claim.record().key();
        StoredClaim storedClaim = loadClaim(key)
                .orElseThrow(PendingHandoffVersionConflictException::new);
        long now = System.currentTimeMillis();
        if (!storedClaim.ownerToken().equals(claim.ownerToken())
                || storedClaim.leaseUntil() != claim.leaseUntil()
                || storedClaim.leaseUntil() <= now) {
            throw new PendingHandoffVersionConflictException();
        }
        try {
            int changed = databaseClient.update("""
                    UPDATE cs_pending_handoff
                    SET status = ?, failure_type = ?, attempt_count = attempt_count + 1,
                        next_attempt_at = ?, version = version + 1, updated_at = ?
                    WHERE tenant_id = ?
                      AND idempotency_key = ?
                      AND version = ?
                      AND status = ?
                      AND EXISTS (
                          SELECT 1 FROM cs_pending_handoff_claim
                          WHERE tenant_id = ? AND idempotency_key = ?
                            AND owner_token = ? AND lease_until = ? AND lease_until > ?
                      )
                    """,
                    targetStatus.name(),
                    safeFailureType(failureType),
                    nextAttemptAt,
                    now,
                    key.tenantId(),
                    key.idempotencyKey(),
                    claim.record().version(),
                    PendingHandoffStatus.PROCESSING.name(),
                    key.tenantId(),
                    key.idempotencyKey(),
                    claim.ownerToken(),
                    claim.leaseUntil(),
                    now);
            if (changed != 1) {
                throw new PendingHandoffVersionConflictException();
            }
            return findByKey(key)
                    .orElseThrow(() -> new IllegalStateException("Finished pending handoff could not be loaded"));
        } finally {
            releaseClaimRow(key, claim.ownerToken());
        }
    }

    private Optional<StoredClaim> loadClaim(PendingHandoffKey key) {
        return databaseClient.queryOne("""
                        SELECT owner_token, lease_until
                        FROM cs_pending_handoff_claim
                        WHERE tenant_id = ? AND idempotency_key = ?
                        """,
                resultSet -> new StoredClaim(
                        resultSet.getString("owner_token"),
                        resultSet.getLong("lease_until")
                ),
                key.tenantId(),
                key.idempotencyKey());
    }

    private void releaseClaimRow(PendingHandoffKey key, String ownerToken) {
        databaseClient.update("""
                DELETE FROM cs_pending_handoff_claim
                WHERE tenant_id = ? AND idempotency_key = ? AND owner_token = ?
                """,
                key.tenantId(),
                key.idempotencyKey(),
                ownerToken);
    }

    private Optional<PendingHandoffRecord> findByTenantAndOutboxId(
            JdbcDatabaseOperations operations,
            String tenantId,
            String outboxId) {
        Optional<PendingHandoffKey> key = operations.queryOne("""
                        SELECT tenant_id, idempotency_key
                        FROM cs_pending_handoff
                        WHERE tenant_id = ? AND outbox_id = ?
                        """,
                this::mapKey,
                tenantId,
                outboxId);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return loadStored(operations, key.get()).map(StoredPendingHandoff::record);
    }

    private Optional<PendingHandoffRecord> findByTenantAndOutboxIdForUpdate(
            JdbcDatabaseOperations operations,
            String tenantId,
            String outboxId) {
        Optional<PendingHandoffKey> key = operations.queryOne("""
                        SELECT tenant_id, idempotency_key
                        FROM cs_pending_handoff
                        WHERE tenant_id = ? AND outbox_id = ?
                        FOR UPDATE
                        """,
                this::mapKey,
                tenantId,
                outboxId);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        return loadStored(operations, key.get()).map(StoredPendingHandoff::record);
    }

    /** 按复合业务键锁定主记录；普通 Worker 确认和人工关闭必须竞争同一把数据库行锁。 */
    private Optional<PendingHandoffRecord> findByKeyForUpdate(
            JdbcDatabaseOperations operations,
            PendingHandoffKey key) {
        Optional<PendingHandoffKey> lockedKey = operations.queryOne("""
                        SELECT tenant_id, idempotency_key
                        FROM cs_pending_handoff
                        WHERE tenant_id = ? AND idempotency_key = ?
                        FOR UPDATE
                        """,
                this::mapKey,
                key.tenantId(),
                key.idempotencyKey());
        if (lockedKey.isEmpty()) {
            return Optional.empty();
        }
        return loadStored(operations, lockedKey.get()).map(StoredPendingHandoff::record);
    }

    private PendingHandoffRecord updateManualReviewState(
            String tenantId,
            String outboxId,
            long expectedVersion,
            PendingHandoffStatus targetStatus,
            String failureType,
            long nextAttemptAt,
            long updatedAt) {
        requireText(tenantId, "tenantId");
        requireText(outboxId, "outboxId");
        String normalizedTenant = tenantId.trim();
        String normalizedOutboxId = outboxId.trim();
        return databaseClient.inTransaction(operations -> {
            PendingHandoffRecord current = findByTenantAndOutboxIdForUpdate(
                    operations,
                    normalizedTenant,
                    normalizedOutboxId
            ).orElseThrow(PendingHandoffReviewNotFoundException::new);
            validateManualReviewTransition(current, expectedVersion);
            int changed = operations.update("""
                    UPDATE cs_pending_handoff
                    SET status = ?, failure_type = ?, next_attempt_at = ?,
                        version = version + 1, updated_at = ?
                    WHERE tenant_id = ? AND outbox_id = ?
                      AND version = ? AND status = ?
                    """,
                    targetStatus.name(),
                    failureType,
                    nextAttemptAt,
                    updatedAt,
                    normalizedTenant,
                    normalizedOutboxId,
                    expectedVersion,
                    PendingHandoffStatus.NEEDS_MANUAL_REVIEW.name());
            if (changed != 1) {
                throw new PendingHandoffVersionConflictException();
            }
            return loadStored(operations, current.key())
                    .map(StoredPendingHandoff::record)
                    .orElseThrow(() -> new IllegalStateException(
                            "Updated manual handoff could not be loaded"
                    ));
        });
    }

    private void validateManualReviewTransition(PendingHandoffRecord current,
                                                long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new PendingHandoffVersionConflictException();
        }
        if (current.status() != PendingHandoffStatus.NEEDS_MANUAL_REVIEW) {
            throw new PendingHandoffStateConflictException(current.status());
        }
    }

    private PendingHandoffKey mapKey(ResultSet resultSet) throws SQLException {
        return new PendingHandoffKey(
                resultSet.getString("tenant_id"),
                resultSet.getString("idempotency_key")
        );
    }

    private PendingHandoffReviewCursor cursorOf(PendingHandoffRecord record) {
        return new PendingHandoffReviewCursor(record.updatedAt(), record.outboxId());
    }

    /**
     * 结果表通常是 CONFIRMED 的权威证据，但人工关闭是显式的安全围栏。
     * 这里把规则下推到 LIMIT 之前，避免脏结果行占掉页面名额或把 CLOSED 错列为成功。
     */
    private String reviewStatusPredicate(PendingHandoffStatus status) {
        if (status == PendingHandoffStatus.CONFIRMED) {
            return """
                    (status = ? OR (
                        status <> 'MANUALLY_CLOSED' AND EXISTS (
                            SELECT 1 FROM cs_pending_handoff_result AS result_row
                            WHERE result_row.tenant_id = cs_pending_handoff.tenant_id
                              AND result_row.idempotency_key = cs_pending_handoff.idempotency_key
                        )
                    ))
                    """;
        }
        if (status == PendingHandoffStatus.MANUALLY_CLOSED) {
            // 历史 MANUALLY_CLOSED 行即使错误残留 result，也必须继续出现在人工关闭列表中。
            return "status = ?";
        }
        return """
                status = ? AND NOT EXISTS (
                    SELECT 1 FROM cs_pending_handoff_result AS result_row
                    WHERE result_row.tenant_id = cs_pending_handoff.tenant_id
                      AND result_row.idempotency_key = cs_pending_handoff.idempotency_key
                )
                """;
    }

    private void requireReviewLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private void requireTimestamp(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
    }

    private void ensureSameResult(AfterSalesTicketCreateResult existing,
                                  AfterSalesTicketCreateResult candidate) {
        if (existing == null || !existing.equals(candidate)) {
            throw new PendingHandoffConflictException();
        }
    }

    private String safeFailureType(String failureType) {
        if (failureType == null || failureType.isBlank()) {
            return "UNKNOWN";
        }
        String compact = failureType.replaceAll("[^A-Za-z0-9_$.-]", "_");
        return compact.length() <= 128 ? compact : compact.substring(0, 128);
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }

    private long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record StoredPendingHandoff(String fingerprint, PendingHandoffRecord record) {
    }

    private record StoredClaim(String ownerToken, long leaseUntil) {
    }
}
