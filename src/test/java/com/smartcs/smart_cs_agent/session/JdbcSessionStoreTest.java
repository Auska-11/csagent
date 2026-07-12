package com.smartcs.smart_cs_agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import com.smartcs.smart_cs_agent.session.core.ConversationStateCodec;
import com.smartcs.smart_cs_agent.session.jdbc.JdbcSessionStore;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JdbcSessionStore 的 SQL 参数、恢复和 CAS 契约测试。
 *
 * <p>CI 默认没有 MySQL，因此这里用一个很小的 JdbcDatabaseClient 测试替身执行 RowMapper 并模拟
 * 复合主键和版本条件。真实 MySQL/Testcontainers 验证仍属于后续生产化工作。</p>
 */
class JdbcSessionStoreTest {

    @Test
    void shouldPersistAcrossStoreRecreationAndRejectStaleRevision() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        ConversationStateCodec codec = new ConversationStateCodec(new ObjectMapper());
        JdbcSessionStore firstStore = new JdbcSessionStore(database, codec);
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "jdbc-conv");
        ConversationState state = new ConversationState("first", "USER-001");

        SessionRecord created = firstStore.save(key, 0, state.toPersistenceSnapshot());
        assertEquals(1, created.version());
        assertTrue(database.createTableSql.contains("idx_cs_agent_session_updated_at"));

        // 新建另一个 JdbcSessionStore，模拟应用重启后重新构造 Adapter。
        JdbcSessionStore restartedStore = new JdbcSessionStore(database, codec);
        SessionRecord restored = restartedStore.load(key).orElseThrow();
        assertEquals("first", restored.snapshot().message());
        assertEquals(1, restored.version());

        state.startNewTurn("second");
        SessionRecord updated = restartedStore.save(
                key,
                restored.version(),
                state.toPersistenceSnapshot()
        );
        assertEquals(2, updated.version());
        assertTrue(database.lastUpdateSql.contains("AND version = ?"));

        SessionVersionConflictException conflict = assertThrows(
                SessionVersionConflictException.class,
                () -> firstStore.save(key, created.version(), state.toPersistenceSnapshot())
        );
        assertEquals(2, conflict.getActualVersion());

        assertThrows(
                SessionVersionConflictException.class,
                () -> firstStore.delete(key, 1)
        );
        assertTrue(restartedStore.delete(key, 2));
        assertFalse(restartedStore.load(key).isPresent());
    }

    private static final class RecordingJdbcDatabaseClient extends JdbcDatabaseClient {

        private final Map<ConversationKey, Row> rows = new ConcurrentHashMap<>();
        private String createTableSql = "";
        private String lastUpdateSql = "";

        private RecordingJdbcDatabaseClient() {
            super("memory", "", "", "", "");
        }

        @Override
        public int update(String sql, Object... args) {
            String normalized = sql.trim();
            if (normalized.startsWith("CREATE TABLE")) {
                createTableSql = sql;
                return 0;
            }
            if (normalized.startsWith("INSERT INTO cs_agent_session")) {
                ConversationKey key = key(args[0], args[1], args[2]);
                Row row = new Row(
                        args[3].toString(),
                        number(args[4]).intValue(),
                        number(args[5]).longValue(),
                        number(args[7]).longValue()
                );
                if (rows.putIfAbsent(key, row) != null) {
                    throw new IllegalStateException("duplicate primary key");
                }
                return 1;
            }
            if (normalized.startsWith("UPDATE cs_agent_session")) {
                lastUpdateSql = sql;
                ConversationKey key = key(args[4], args[5], args[6]);
                long expectedVersion = number(args[7]).longValue();
                Row existing = rows.get(key);
                if (existing == null || existing.version != expectedVersion) {
                    return 0;
                }
                rows.put(key, new Row(
                        args[0].toString(),
                        number(args[1]).intValue(),
                        number(args[2]).longValue(),
                        number(args[3]).longValue()
                ));
                return 1;
            }
            if (normalized.startsWith("DELETE FROM cs_agent_session")) {
                ConversationKey key = key(args[0], args[1], args[2]);
                long expectedVersion = number(args[3]).longValue();
                Row existing = rows.get(key);
                if (existing == null || existing.version != expectedVersion) {
                    return 0;
                }
                return rows.remove(key, existing) ? 1 : 0;
            }
            throw new IllegalArgumentException("Unexpected SQL: " + sql);
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            ConversationKey key = key(args[0], args[1], args[2]);
            Row row = rows.get(key);
            if (row == null) {
                return Optional.empty();
            }

            ResultSet resultSet = mock(ResultSet.class);
            try {
                when(resultSet.getString("state_json")).thenReturn(row.stateJson);
                when(resultSet.getInt("schema_version")).thenReturn(row.schemaVersion);
                when(resultSet.getLong("version")).thenReturn(row.version);
                when(resultSet.getLong("updated_at")).thenReturn(row.updatedAt);
                return Optional.of(mapper.map(resultSet));
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to execute test RowMapper", e);
            }
        }

        private ConversationKey key(Object tenantId, Object userId, Object conversationId) {
            return new ConversationKey(
                    tenantId.toString(),
                    userId.toString(),
                    conversationId.toString()
            );
        }

        private Number number(Object value) {
            return (Number) value;
        }
    }

    private record Row(String stateJson, int schemaVersion, long version, long updatedAt) {
    }
}
