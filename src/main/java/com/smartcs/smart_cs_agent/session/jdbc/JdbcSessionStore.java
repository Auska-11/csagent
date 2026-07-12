package com.smartcs.smart_cs_agent.session.jdbc;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStateSnapshot;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionStore;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import com.smartcs.smart_cs_agent.session.core.ConversationStateCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/** MySQL 会话持久化适配器。 */
@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
public class JdbcSessionStore implements SessionStore {

    private final JdbcDatabaseClient databaseClient;
    private final ConversationStateCodec codec;

    public JdbcSessionStore(JdbcDatabaseClient databaseClient, ConversationStateCodec codec) {
        this.databaseClient = databaseClient;
        this.codec = codec;
        initTable();
    }

    private void initTable() {
        databaseClient.update("""
                CREATE TABLE IF NOT EXISTS cs_agent_session (
                    tenant_id VARCHAR(64) NOT NULL,
                    user_id VARCHAR(64) NOT NULL,
                    conversation_id VARCHAR(128) NOT NULL,
                    state_json LONGTEXT NOT NULL,
                    schema_version INT NOT NULL,
                    version BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (tenant_id, user_id, conversation_id),
                    INDEX idx_cs_agent_session_updated_at (updated_at)
                )
                """);
    }

    @Override
    public Optional<SessionRecord> load(ConversationKey key) {
        return databaseClient.queryOne("""
                        SELECT state_json, schema_version, version, updated_at
                        FROM cs_agent_session
                        WHERE tenant_id = ? AND user_id = ? AND conversation_id = ?
                        """,
                resultSet -> {
                    int schemaVersion = resultSet.getInt("schema_version");
                    ConversationStateSnapshot snapshot = codec.decode(
                            resultSet.getString("state_json"),
                            schemaVersion
                    );
                    return new SessionRecord(
                            key,
                            snapshot,
                            resultSet.getLong("version"),
                            resultSet.getLong("updated_at")
                    );
                },
                key.tenantId(),
                key.userId(),
                key.conversationId());
    }

    @Override
    public SessionRecord save(
            ConversationKey key,
            long expectedVersion,
            ConversationStateSnapshot snapshot) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion cannot be negative");
        }

        // SessionStore 是公开 SPI，不能假设每个调用方都先经过 ConversationStore。
        // 所有权必须在 JSON 编码和任何 INSERT/UPDATE 之前校验；否则虽然返回异常，错误数据却已经入库。
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (!key.userId().equals(snapshot.userId())) {
            throw new IllegalArgumentException("session snapshot owner does not match key");
        }

        String stateJson = codec.encode(snapshot);
        long now = System.currentTimeMillis();
        long nextVersion = expectedVersion + 1;
        int changedRows;

        if (expectedVersion == 0) {
            try {
                changedRows = databaseClient.update("""
                        INSERT INTO cs_agent_session(
                            tenant_id, user_id, conversation_id, state_json,
                            schema_version, version, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        key.tenantId(),
                        key.userId(),
                        key.conversationId(),
                        stateJson,
                        snapshot.schemaVersion(),
                        nextVersion,
                        now,
                        now);
            } catch (JdbcDuplicateKeyException duplicateKey) {
                // 复合主键已被另一个执行者创建，读取实际版本后转成统一 CAS 冲突。
                Optional<SessionRecord> existing = load(key);
                if (existing.isPresent()) {
                    throw new SessionVersionConflictException(
                            key,
                            expectedVersion,
                            existing.get().version()
                    );
                }
                throw duplicateKey;
            }
        } else {
            changedRows = databaseClient.update("""
                    UPDATE cs_agent_session
                    SET state_json = ?,
                        schema_version = ?,
                        version = ?,
                        updated_at = ?
                    WHERE tenant_id = ?
                      AND user_id = ?
                      AND conversation_id = ?
                      AND version = ?
                    """,
                    stateJson,
                    snapshot.schemaVersion(),
                    nextVersion,
                    now,
                    key.tenantId(),
                    key.userId(),
                    key.conversationId(),
                    expectedVersion);
        }

        if (changedRows != 1) {
            long actualVersion = load(key).map(SessionRecord::version).orElse(0L);
            throw new SessionVersionConflictException(key, expectedVersion, actualVersion);
        }
        return new SessionRecord(key, snapshot, nextVersion, now);
    }

    @Override
    public boolean delete(ConversationKey key, long expectedVersion) {
        if (expectedVersion <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        int changedRows = databaseClient.update("""
                DELETE FROM cs_agent_session
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND conversation_id = ?
                  AND version = ?
                """,
                key.tenantId(),
                key.userId(),
                key.conversationId(),
                expectedVersion);
        if (changedRows == 1) {
            return true;
        }

        Optional<SessionRecord> existing = load(key);
        if (existing.isEmpty()) {
            return false;
        }
        throw new SessionVersionConflictException(key, expectedVersion, existing.get().version());
    }
}
