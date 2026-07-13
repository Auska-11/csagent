package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MySQL 人工转接 Outbox。
 *
 * <p>唯一键保证相同外部 idempotencyKey 只能保存一条记录；请求指纹保证 key 被错误复用于不同参数时
 * 立即失败。表中保存完整远端请求，是因为进程重启后后台任务仍需要用相同参数和 key 核对或补建。</p>
 */
@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
public class JdbcPendingHandoffStore implements PendingHandoffStore {

    private final JdbcDatabaseClient databaseClient;

    public JdbcPendingHandoffStore(JdbcDatabaseClient databaseClient) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient cannot be null");
        initTable();
    }

    private void initTable() {
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_pending_handoff (
                    outbox_id CHAR(36) NOT NULL,
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
                    UNIQUE KEY uk_cs_pending_handoff_idempotency (idempotency_key),
                    INDEX idx_cs_pending_handoff_due (status, next_attempt_at)
                )
                """);
        // 成功结果使用独立的追加式表保存，避免 CREATE TABLE IF NOT EXISTS 无法升级上一批已创建的主表。
        // 结果行一旦存在就是远端成功的证据；即使更新主表状态时进程退出，重启后仍能从这里回放。
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_pending_handoff_result (
                    idempotency_key VARCHAR(255) NOT NULL,
                    ticket_id VARCHAR(128) NOT NULL,
                    ticket_status VARCHAR(64) NOT NULL,
                    ticket_created_at VARCHAR(128) NOT NULL,
                    recorded_at BIGINT NOT NULL,
                    PRIMARY KEY (idempotency_key)
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
                        outbox_id, idempotency_key, request_fingerprint_hash,
                        user_id, conversation_id, reason, summary_text,
                        request_title, request_description, customer_id,
                        product_line, issue_type, priority_code,
                        status, failure_type, attempt_count, next_attempt_at,
                        version, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    outboxId,
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
                    now,
                    1,
                    now,
                    now
            );
            if (changed != 1) {
                throw new IllegalStateException("Pending handoff insert did not change exactly one row");
            }
        } catch (JdbcDuplicateKeyException duplicateKey) {
            // 并发请求可能同时插入同一个 key。唯一键决定胜者，失败者读取既有记录并校验指纹。
            StoredPendingHandoff existing = loadStored(request.idempotencyKey())
                    .orElseThrow(() -> duplicateKey);
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new PendingHandoffConflictException();
            }
            return new PendingHandoffPreparation(existing.record(), false);
        }
        PendingHandoffRecord inserted = findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Inserted pending handoff could not be loaded"));
        return new PendingHandoffPreparation(inserted, true);
    }

    @Override
    public PendingHandoffRecord markResultUnknown(String idempotencyKey,
                                                  String failureType,
                                                  long expectedVersion) {
        requireText(idempotencyKey, "idempotencyKey");
        long now = System.currentTimeMillis();
        int changed = databaseClient.update("""
                UPDATE cs_pending_handoff
                SET status = ?, failure_type = ?, attempt_count = attempt_count + 1,
                    next_attempt_at = ?, version = version + 1, updated_at = ?
                WHERE idempotency_key = ?
                  AND version = ?
                  AND status IN (?, ?)
                """,
                PendingHandoffStatus.RESULT_UNKNOWN.name(),
                safeFailureType(failureType),
                now,
                now,
                idempotencyKey,
                expectedVersion,
                PendingHandoffStatus.PREPARED.name(),
                PendingHandoffStatus.RESULT_UNKNOWN.name());
        if (changed == 1) {
            return findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Updated pending handoff could not be loaded"));
        }
        PendingHandoffRecord current = findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalArgumentException("Pending handoff record does not exist"));
        if (current.status() == PendingHandoffStatus.CONFIRMED) {
            // 另一个核对执行者已经取得真实成功结果。成功证据优先于当前线程的网络异常。
            return current;
        }
        throw new PendingHandoffVersionConflictException();
    }

    @Override
    public PendingHandoffRecord confirm(String idempotencyKey,
                                        AfterSalesTicketCreateResult result,
                                        long expectedVersion) {
        requireText(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(result, "result cannot be null");
        PendingHandoffRecord existing = findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalArgumentException("Pending handoff record does not exist"));
        if (existing.status() == PendingHandoffStatus.CONFIRMED) {
            ensureSameResult(existing.confirmedResult(), result);
            return existing;
        }
        if (existing.version() != expectedVersion) {
            throw new PendingHandoffVersionConflictException();
        }

        saveConfirmedResult(idempotencyKey, result);

        // 成功结果表已经成为权威证据，所以这里允许将任意非终态推进为 CONFIRMED。
        // 如果进程在 INSERT 结果后、UPDATE 主表前退出，loadStored 也会根据结果行恢复成 CONFIRMED。
        long now = System.currentTimeMillis();
        databaseClient.update("""
                UPDATE cs_pending_handoff
                SET status = ?, failure_type = ?, attempt_count = attempt_count + 1,
                    next_attempt_at = 0, version = version + 1, updated_at = ?
                WHERE idempotency_key = ? AND status <> ?
                """,
                PendingHandoffStatus.CONFIRMED.name(),
                "NONE",
                now,
                idempotencyKey,
                PendingHandoffStatus.CONFIRMED.name());

        PendingHandoffRecord confirmed = findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Confirmed pending handoff could not be loaded"));
        ensureSameResult(confirmed.confirmedResult(), result);
        return confirmed;
    }

    @Override
    public Optional<PendingHandoffRecord> findByIdempotencyKey(String idempotencyKey) {
        requireText(idempotencyKey, "idempotencyKey");
        return loadStored(idempotencyKey).map(StoredPendingHandoff::record);
    }

    private Optional<StoredPendingHandoff> loadStored(String idempotencyKey) {
        Optional<StoredPendingHandoff> base = databaseClient.queryOne("""
                        SELECT outbox_id, idempotency_key, request_fingerprint_hash,
                               user_id, conversation_id, reason, summary_text,
                               request_title, request_description, customer_id,
                               product_line, issue_type, priority_code,
                               status, failure_type, attempt_count, next_attempt_at,
                               version, created_at, updated_at
                        FROM cs_pending_handoff
                        WHERE idempotency_key = ?
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
                idempotencyKey);
        if (base.isEmpty()) {
            return Optional.empty();
        }

        Optional<AfterSalesTicketCreateResult> confirmedResult = loadConfirmedResult(idempotencyKey);
        if (confirmedResult.isEmpty()) {
            return base;
        }
        StoredPendingHandoff stored = base.get();
        PendingHandoffRecord current = stored.record();
        PendingHandoffRecord confirmed = new PendingHandoffRecord(
                current.outboxId(),
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

    private void saveConfirmedResult(String idempotencyKey, AfterSalesTicketCreateResult result) {
        try {
            databaseClient.update("""
                    INSERT INTO cs_pending_handoff_result(
                        idempotency_key, ticket_id, ticket_status, ticket_created_at, recorded_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    idempotencyKey,
                    result.ticketId(),
                    result.status(),
                    result.createdAt(),
                    System.currentTimeMillis());
        } catch (JdbcDuplicateKeyException duplicateKey) {
            AfterSalesTicketCreateResult existing = loadConfirmedResult(idempotencyKey)
                    .orElseThrow(() -> duplicateKey);
            ensureSameResult(existing, result);
        }
    }

    private Optional<AfterSalesTicketCreateResult> loadConfirmedResult(String idempotencyKey) {
        return databaseClient.queryOne("""
                        SELECT ticket_id, ticket_status, ticket_created_at
                        FROM cs_pending_handoff_result
                        WHERE idempotency_key = ?
                        """,
                resultSet -> new AfterSalesTicketCreateResult(
                        resultSet.getString("ticket_id"),
                        resultSet.getString("ticket_status"),
                        resultSet.getString("ticket_created_at")
                ),
                idempotencyKey);
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

    private record StoredPendingHandoff(String fingerprint, PendingHandoffRecord record) {
    }
}
