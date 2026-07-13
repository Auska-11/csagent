package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
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
    }

    @Override
    public PendingHandoffRecord recordUnknown(PendingHandoffCommand command) {
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
                    PendingHandoffStatus.RESULT_UNKNOWN.name(),
                    safeFailureType(command.failureType()),
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
            return existing.record();
        }
        return findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Inserted pending handoff could not be loaded"));
    }

    @Override
    public Optional<PendingHandoffRecord> findByIdempotencyKey(String idempotencyKey) {
        requireText(idempotencyKey, "idempotencyKey");
        return loadStored(idempotencyKey).map(StoredPendingHandoff::record);
    }

    private Optional<StoredPendingHandoff> loadStored(String idempotencyKey) {
        return databaseClient.queryOne("""
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
    }

    private String safeFailureType(String failureType) {
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
