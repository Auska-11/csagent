package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JDBC Outbox 的 SQL 契约测试。
 *
 * <p>这里使用数据库替身验证唯一键、重建读取和冲突语义；真实 MySQL 容器故障会在后续验收批次补充。</p>
 */
class JdbcPendingHandoffStoreTest {

    @Test
    void shouldRecoverPendingRequestAfterStoreIsRecreated() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore firstStore = new JdbcPendingHandoffStore(database);
        PendingHandoffCommand command = command("idem-jdbc", "需要人工核对");

        PendingHandoffRecord first = firstStore.recordUnknown(command);
        JdbcPendingHandoffStore restartedStore = new JdbcPendingHandoffStore(database);
        PendingHandoffRecord recovered = restartedStore.findByIdempotencyKey("idem-jdbc").orElseThrow();

        assertEquals(first.outboxId(), recovered.outboxId());
        assertEquals(PendingHandoffStatus.RESULT_UNKNOWN, recovered.status());
        assertEquals("需要人工核对", recovered.summary());
        assertEquals("idem-jdbc", recovered.request().idempotencyKey());
        assertTrue(database.createTableSql.contains("UNIQUE KEY uk_cs_pending_handoff_idempotency"));
        assertTrue(database.createTableSql.contains("INDEX idx_cs_pending_handoff_due"));
    }

    @Test
    void shouldReplaySameInsertAndRejectDifferentPayload() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database);

        PendingHandoffRecord first = store.recordUnknown(command("idem-unique", "原摘要"));
        PendingHandoffRecord replay = store.recordUnknown(command("idem-unique", "原摘要"));

        assertEquals(first.outboxId(), replay.outboxId());
        assertEquals(1, database.rows.size());
        assertThrows(
                PendingHandoffConflictException.class,
                () -> store.recordUnknown(command("idem-unique", "不同摘要"))
        );
    }

    private PendingHandoffCommand command(String idempotencyKey, String summary) {
        return new PendingHandoffCommand(
                "USER-001",
                "conv-jdbc",
                "COMPLAINT",
                summary,
                new AfterSalesTicketCreateRequest(
                        "Agent 转人工：投诉",
                        "用户投诉，需要人工处理",
                        "USER-001",
                        "general",
                        "complaint",
                        "URGENT",
                        idempotencyKey
                ),
                "IllegalStateException"
        );
    }

    private static final class RecordingJdbcDatabaseClient extends JdbcDatabaseClient {

        private final Map<String, Row> rows = new ConcurrentHashMap<>();
        private String createTableSql = "";

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
            if (normalized.startsWith("INSERT INTO cs_pending_handoff")) {
                String idempotencyKey = args[1].toString();
                Row row = new Row(args.clone());
                if (rows.putIfAbsent(idempotencyKey, row) != null) {
                    throw new JdbcDuplicateKeyException(
                            new SQLException("duplicate idempotency key", "23000", 1062)
                    );
                }
                return 1;
            }
            throw new IllegalArgumentException("Unexpected SQL: " + sql);
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            Row row = rows.get(args[0].toString());
            if (row == null) {
                return Optional.empty();
            }
            ResultSet resultSet = mock(ResultSet.class);
            try {
                Object[] values = row.values;
                when(resultSet.getString("outbox_id")).thenReturn(values[0].toString());
                when(resultSet.getString("idempotency_key")).thenReturn(values[1].toString());
                when(resultSet.getString("request_fingerprint_hash")).thenReturn(values[2].toString());
                when(resultSet.getString("user_id")).thenReturn(values[3].toString());
                when(resultSet.getString("conversation_id")).thenReturn(values[4].toString());
                when(resultSet.getString("reason")).thenReturn(values[5].toString());
                when(resultSet.getString("summary_text")).thenReturn(values[6].toString());
                when(resultSet.getString("request_title")).thenReturn(values[7].toString());
                when(resultSet.getString("request_description")).thenReturn(values[8].toString());
                when(resultSet.getString("customer_id")).thenReturn(values[9].toString());
                when(resultSet.getString("product_line")).thenReturn(values[10].toString());
                when(resultSet.getString("issue_type")).thenReturn(values[11].toString());
                when(resultSet.getString("priority_code")).thenReturn(values[12].toString());
                when(resultSet.getString("status")).thenReturn(values[13].toString());
                when(resultSet.getString("failure_type")).thenReturn(values[14].toString());
                when(resultSet.getInt("attempt_count")).thenReturn(number(values[15]).intValue());
                when(resultSet.getLong("next_attempt_at")).thenReturn(number(values[16]).longValue());
                when(resultSet.getLong("version")).thenReturn(number(values[17]).longValue());
                when(resultSet.getLong("created_at")).thenReturn(number(values[18]).longValue());
                when(resultSet.getLong("updated_at")).thenReturn(number(values[19]).longValue());
                return Optional.of(mapper.map(resultSet));
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to execute test RowMapper", e);
            }
        }

        private static Number number(Object value) {
            return (Number) value;
        }

        private record Row(Object[] values) {
        }
    }
}
