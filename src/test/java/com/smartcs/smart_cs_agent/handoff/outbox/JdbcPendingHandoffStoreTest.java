package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
 * JDBC Outbox 的 SQL 契约测试。
 *
 * <p>这里使用数据库替身验证调用前准备、唯一键、CAS 和成功结果重建；真实 MySQL 容器故障会在后续
 * 验收批次补充。替身故意保留 SQL 参数位置，让初学者可以把测试和 JdbcPendingHandoffStore 对照阅读。</p>
 */
class JdbcPendingHandoffStoreTest {

    private static final String TENANT = "tenant-a";

    @Test
    void shouldRecoverPreparedRequestAfterStoreIsRecreated() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore firstStore = new JdbcPendingHandoffStore(database);
        PendingHandoffCommand command = command("idem-jdbc", "需要人工核对");

        PendingHandoffPreparation first = firstStore.prepare(command);
        JdbcPendingHandoffStore restartedStore = new JdbcPendingHandoffStore(database);
        PendingHandoffRecord recovered = restartedStore.findByKey(command.key()).orElseThrow();

        assertTrue(first.created());
        assertEquals(first.record().outboxId(), recovered.outboxId());
        assertEquals(PendingHandoffStatus.PREPARED, recovered.status());
        assertEquals("需要人工核对", recovered.summary());
        assertEquals("idem-jdbc", recovered.request().idempotencyKey());
        assertTrue(database.createTableSqls.stream()
                .anyMatch(sql -> sql.contains("UNIQUE KEY uk_cs_pending_handoff_tenant_idempotency")));
        assertTrue(database.createTableSqls.stream()
                .anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS cs_pending_handoff_result")));
    }

    @Test
    void shouldReplaySamePreparationAndRejectDifferentPayload() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database);

        PendingHandoffPreparation first = store.prepare(command("idem-unique", "原摘要"));
        PendingHandoffPreparation replay = store.prepare(command("idem-unique", "原摘要"));

        assertTrue(first.created());
        assertFalse(replay.created());
        assertEquals(first.record().outboxId(), replay.record().outboxId());
        assertEquals(1, database.rows.size());
        assertThrows(
                PendingHandoffConflictException.class,
                () -> store.prepare(command("idem-unique", "不同摘要"))
        );
    }

    @Test
    void shouldAdvanceResultUnknownWithVersionCas() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database);
        PendingHandoffRecord prepared = store.prepare(command("idem-unknown", "等待核对")).record();

        PendingHandoffRecord unknown = store.markResultUnknown(
                prepared.key(),
                "Socket Timeout\nInjected",
                prepared.version()
        );

        assertEquals(PendingHandoffStatus.RESULT_UNKNOWN, unknown.status());
        assertEquals("Socket_Timeout_Injected", unknown.failureType());
        assertEquals(1, unknown.attemptCount());
        assertEquals(2, unknown.version());
        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.markResultUnknown(prepared.key(), "Again", prepared.version())
        );
    }

    @Test
    void shouldPersistConfirmedResultAndReplayAfterRestart() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database);
        PendingHandoffCommand command = command("idem-confirmed", "已经确认");
        PendingHandoffRecord prepared = store.prepare(command).record();
        AfterSalesTicketCreateResult result = result("AS-900");

        PendingHandoffRecord confirmed = store.confirm(
                prepared.key(),
                result,
                prepared.version()
        );
        PendingHandoffRecord lateTimeout = store.markResultUnknown(
                prepared.key(), "SocketTimeoutException", confirmed.version()
        );
        JdbcPendingHandoffStore restartedStore = new JdbcPendingHandoffStore(database);
        PendingHandoffPreparation replay = restartedStore.prepare(command);

        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals(result, confirmed.confirmedResult());
        assertEquals(PendingHandoffStatus.CONFIRMED, lateTimeout.status());
        assertFalse(replay.created());
        assertEquals(result, replay.record().confirmedResult());
        assertEquals(1, database.results.size());
    }

    @Test
    void shouldRecoverSuccessWhenProcessStopsBeforeMainStatusUpdate() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        database.skipNextConfirmationStatusUpdate = true;
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database);
        PendingHandoffRecord prepared = store.prepare(command("idem-partial", "部分提交")).record();

        PendingHandoffRecord confirmed = store.confirm(
                prepared.key(),
                result("AS-901"),
                prepared.version()
        );

        // 主表仍是 PREPARED，但追加式结果表已经成为成功证据，读取时必须呈现 CONFIRMED。
        assertEquals(PendingHandoffStatus.PREPARED, database.statusOf(prepared.key()));
        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals("AS-901", confirmed.confirmedResult().ticketId());
    }

    @Test
    void shouldClaimOnceTakeOverExpiredLeaseAndScheduleRetry() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database);
        PendingHandoffRecord prepared = store.prepare(command("idem-jdbc-lease", "JDBC 租约")).record();
        long now = prepared.nextAttemptAt();

        assertEquals(1, store.findDue(now, 10).size());
        PendingHandoffClaim first = store.tryClaim(
                prepared.key(), prepared.version(), "owner-1", now, 1_000
        ).orElseThrow();
        assertTrue(store.tryClaim(
                prepared.key(), first.record().version(), "owner-2", now + 500, 1_000
        ).isEmpty());

        PendingHandoffClaim takeover = store.tryClaim(
                prepared.key(), first.record().version(), "owner-2", now + 1_000, 1_000
        ).orElseThrow();
        PendingHandoffRecord retry = store.scheduleRetry(
                takeover, "SocketTimeoutException", now + 5_000
        );

        assertEquals(PendingHandoffStatus.RETRY_SCHEDULED, retry.status());
        assertEquals(1, retry.attemptCount());
        assertTrue(database.claims.isEmpty());
        assertTrue(store.findDue(now + 4_999, 10).isEmpty());
        assertEquals(1, store.findDue(now + 5_000, 10).size());
    }

    @Test
    void shouldKeepSameIdempotencyKeyIndependentAcrossTenants() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database);
        PendingHandoffCommand tenantA = command("tenant-a", "shared-key", "A 租户");
        PendingHandoffCommand tenantB = command("tenant-b", "shared-key", "B 租户");

        PendingHandoffRecord savedA = store.prepare(tenantA).record();
        PendingHandoffRecord savedB = store.prepare(tenantB).record();

        assertEquals(2, database.rows.size());
        assertEquals("A 租户", store.findByKey(tenantA.key()).orElseThrow().summary());
        assertEquals("B 租户", store.findByKey(tenantB.key()).orElseThrow().summary());

        store.confirm(tenantA.key(), result("AS-A"), savedA.version());
        store.confirm(tenantB.key(), result("AS-B"), savedB.version());
        assertEquals("AS-A", store.findByKey(tenantA.key()).orElseThrow().confirmedResult().ticketId());
        assertEquals("AS-B", store.findByKey(tenantB.key()).orElseThrow().confirmedResult().ticketId());
        assertEquals(2, database.results.size());
    }

    @Test
    void shouldKeepJdbcClaimsIndependentAndHideUnknownTenant() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database);
        PendingHandoffRecord tenantA = store.prepare(command("tenant-a", "claim-key", "A")).record();
        PendingHandoffRecord tenantB = store.prepare(command("tenant-b", "claim-key", "B")).record();
        long now = Math.max(tenantA.nextAttemptAt(), tenantB.nextAttemptAt());

        assertTrue(store.tryClaim(
                tenantA.key(), tenantA.version(), "owner-a", now, 1_000
        ).isPresent());
        assertTrue(store.tryClaim(
                tenantB.key(), tenantB.version(), "owner-b", now, 1_000
        ).isPresent());
        assertEquals(2, database.claims.size());
        assertTrue(store.tryClaim(
                new PendingHandoffKey("tenant-c", "claim-key"), 1, "owner-c", now, 1_000
        ).isEmpty());
    }

    private PendingHandoffCommand command(String idempotencyKey, String summary) {
        return command(TENANT, idempotencyKey, summary);
    }

    private PendingHandoffCommand command(String tenantId, String idempotencyKey, String summary) {
        return new PendingHandoffCommand(
                tenantId,
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
                )
        );
    }

    private AfterSalesTicketCreateResult result(String ticketId) {
        return new AfterSalesTicketCreateResult(ticketId, "PENDING", "2026-07-13T00:00:00Z");
    }

    /**
     * 只模拟本 Store 使用到的 SQL 语义，不尝试实现通用数据库。
     * Object[] 的字段顺序与 INSERT 参数一致，UPDATE 会原地替换为新的不可变 Row。
     */
    private static final class RecordingJdbcDatabaseClient extends JdbcDatabaseClient {

        private final Map<PendingHandoffKey, Row> rows = new ConcurrentHashMap<>();
        private final Map<PendingHandoffKey, ResultRow> results = new ConcurrentHashMap<>();
        private final Map<PendingHandoffKey, ClaimRow> claims = new ConcurrentHashMap<>();
        private final List<String> createTableSqls = new ArrayList<>();
        private boolean skipNextConfirmationStatusUpdate;

        private RecordingJdbcDatabaseClient() {
            super("memory", "", "", "", "");
        }

        @Override
        public int update(String sql, Object... args) {
            String normalized = sql.trim();
            if (normalized.startsWith("CREATE TABLE")) {
                createTableSqls.add(sql);
                return 0;
            }
            // 必须先判断更长的结果表前缀，否则它也会匹配 cs_pending_handoff。
            if (normalized.startsWith("INSERT INTO cs_pending_handoff_result")) {
                PendingHandoffKey key = key(args[0], args[1]);
                ResultRow candidate = new ResultRow(
                        args[2].toString(),
                        args[3].toString(),
                        args[4].toString()
                );
                if (results.putIfAbsent(key, candidate) != null) {
                    throw duplicate();
                }
                return 1;
            }
            if (normalized.startsWith("INSERT INTO cs_pending_handoff_claim")) {
                PendingHandoffKey key = key(args[0], args[1]);
                ClaimRow candidate = new ClaimRow(
                        args[2].toString(), number(args[3]).longValue()
                );
                if (claims.putIfAbsent(key, candidate) != null) {
                    throw duplicate();
                }
                return 1;
            }
            if (normalized.startsWith("INSERT INTO cs_pending_handoff")) {
                PendingHandoffKey key = key(args[1], args[2]);
                if (rows.putIfAbsent(key, new Row(args.clone())) != null) {
                    throw duplicate();
                }
                return 1;
            }
            if (normalized.startsWith("UPDATE cs_pending_handoff")
                    && normalized.contains("SET status = ?, next_attempt_at")) {
                return markProcessing(args);
            }
            if (normalized.startsWith("UPDATE cs_pending_handoff")
                    && normalized.contains("status IN")) {
                return markUnknown(args);
            }
            if (normalized.startsWith("UPDATE cs_pending_handoff")
                    && normalized.contains("status <>")) {
                if (skipNextConfirmationStatusUpdate) {
                    skipNextConfirmationStatusUpdate = false;
                    return 0;
                }
                return markConfirmed(args);
            }
            if (normalized.startsWith("UPDATE cs_pending_handoff")
                    && normalized.contains("AND status = ?")) {
                return finishProcessing(args);
            }
            if (normalized.startsWith("UPDATE cs_pending_handoff_claim")) {
                return takeOverClaim(args);
            }
            if (normalized.startsWith("DELETE FROM cs_pending_handoff_claim")) {
                PendingHandoffKey key = key(args[0], args[1]);
                ClaimRow existing = claims.get(key);
                if (existing == null || !existing.ownerToken.equals(args[2].toString())) {
                    return 0;
                }
                return claims.remove(key, existing) ? 1 : 0;
            }
            throw new IllegalArgumentException("Unexpected SQL: " + sql);
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            PendingHandoffKey key = key(args[0], args[1]);
            if (sql.contains("FROM cs_pending_handoff_result")) {
                return mapResult(key, mapper);
            }
            if (sql.contains("FROM cs_pending_handoff_claim")) {
                return mapClaim(key, mapper);
            }
            return mapBase(key, mapper);
        }

        @Override
        public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... args) {
            if (!sql.contains("FROM cs_pending_handoff")) {
                throw new IllegalArgumentException("Unexpected query SQL: " + sql);
            }
            long now = number(args[4]).longValue();
            int limit = number(args[5]).intValue();
            List<T> mapped = new ArrayList<>();
            for (Map.Entry<PendingHandoffKey, Row> entry : rows.entrySet()) {
                Row row = entry.getValue();
                String status = row.values[14].toString();
                boolean acceptedStatus = status.equals(args[0].toString())
                        || status.equals(args[1].toString())
                        || status.equals(args[2].toString())
                        || status.equals(args[3].toString());
                if (!acceptedStatus || number(row.values[17]).longValue() > now) {
                    continue;
                }
                ResultSet resultSet = mock(ResultSet.class);
                try {
                    when(resultSet.getString("tenant_id")).thenReturn(entry.getKey().tenantId());
                    when(resultSet.getString("idempotency_key")).thenReturn(entry.getKey().idempotencyKey());
                    mapped.add(mapper.map(resultSet));
                } catch (SQLException e) {
                    throw new IllegalStateException("Failed to map due key", e);
                }
                if (mapped.size() >= limit) {
                    break;
                }
            }
            return mapped;
        }

        private int markProcessing(Object[] args) {
            PendingHandoffKey key = key(args[3], args[4]);
            Row existing = rows.get(key);
            if (existing == null
                    || number(existing.values[18]).longValue() != number(args[5]).longValue()
                    || number(existing.values[17]).longValue() > number(args[10]).longValue()) {
                return 0;
            }
            String status = existing.values[14].toString();
            boolean accepted = status.equals(args[6].toString())
                    || status.equals(args[7].toString())
                    || status.equals(args[8].toString())
                    || status.equals(args[9].toString());
            if (!accepted) {
                return 0;
            }
            Object[] changed = existing.values.clone();
            changed[14] = args[0];
            changed[17] = args[1];
            changed[18] = number(changed[18]).longValue() + 1;
            changed[20] = args[2];
            rows.put(key, new Row(changed));
            return 1;
        }

        private int markUnknown(Object[] args) {
            PendingHandoffKey key = key(args[4], args[5]);
            Row existing = rows.get(key);
            if (existing == null
                    || number(existing.values[18]).longValue() != number(args[6]).longValue()) {
                return 0;
            }
            String status = existing.values[14].toString();
            if (!status.equals(args[7].toString()) && !status.equals(args[8].toString())) {
                return 0;
            }
            Object[] changed = existing.values.clone();
            changed[14] = args[0];
            changed[15] = args[1];
            changed[16] = number(changed[16]).intValue() + 1;
            changed[17] = args[2];
            changed[18] = number(changed[18]).longValue() + 1;
            changed[20] = args[3];
            rows.put(key, new Row(changed));
            return 1;
        }

        private int markConfirmed(Object[] args) {
            PendingHandoffKey key = key(args[3], args[4]);
            Row existing = rows.get(key);
            if (existing == null || existing.values[14].toString().equals(args[5].toString())) {
                return 0;
            }
            Object[] changed = existing.values.clone();
            changed[14] = args[0];
            changed[15] = args[1];
            changed[16] = number(changed[16]).intValue() + 1;
            changed[17] = 0L;
            changed[18] = number(changed[18]).longValue() + 1;
            changed[20] = args[2];
            rows.put(key, new Row(changed));
            return 1;
        }

        private int finishProcessing(Object[] args) {
            PendingHandoffKey key = key(args[4], args[5]);
            Row existing = rows.get(key);
            if (existing == null
                    || number(existing.values[18]).longValue() != number(args[6]).longValue()
                    || !existing.values[14].toString().equals(args[7].toString())) {
                return 0;
            }
            Object[] changed = existing.values.clone();
            changed[14] = args[0];
            changed[15] = args[1];
            changed[16] = number(changed[16]).intValue() + 1;
            changed[17] = args[2];
            changed[18] = number(changed[18]).longValue() + 1;
            changed[20] = args[3];
            rows.put(key, new Row(changed));
            return 1;
        }

        private int takeOverClaim(Object[] args) {
            PendingHandoffKey key = key(args[3], args[4]);
            ClaimRow existing = claims.get(key);
            if (existing == null
                    || !existing.ownerToken.equals(args[5].toString())
                    || existing.leaseUntil != number(args[6]).longValue()) {
                return 0;
            }
            claims.put(key, new ClaimRow(args[0].toString(), number(args[1]).longValue()));
            return 1;
        }

        private <T> Optional<T> mapBase(PendingHandoffKey key, RowMapper<T> mapper) {
            Row row = rows.get(key);
            if (row == null) {
                return Optional.empty();
            }
            ResultSet resultSet = mock(ResultSet.class);
            try {
                Object[] values = row.values;
                when(resultSet.getString("outbox_id")).thenReturn(values[0].toString());
                when(resultSet.getString("tenant_id")).thenReturn(values[1].toString());
                when(resultSet.getString("idempotency_key")).thenReturn(values[2].toString());
                when(resultSet.getString("request_fingerprint_hash")).thenReturn(values[3].toString());
                when(resultSet.getString("user_id")).thenReturn(values[4].toString());
                when(resultSet.getString("conversation_id")).thenReturn(values[5].toString());
                when(resultSet.getString("reason")).thenReturn(values[6].toString());
                when(resultSet.getString("summary_text")).thenReturn(values[7].toString());
                when(resultSet.getString("request_title")).thenReturn(values[8].toString());
                when(resultSet.getString("request_description")).thenReturn(values[9].toString());
                when(resultSet.getString("customer_id")).thenReturn(values[10].toString());
                when(resultSet.getString("product_line")).thenReturn(values[11].toString());
                when(resultSet.getString("issue_type")).thenReturn(values[12].toString());
                when(resultSet.getString("priority_code")).thenReturn(values[13].toString());
                when(resultSet.getString("status")).thenReturn(values[14].toString());
                when(resultSet.getString("failure_type")).thenReturn(values[15].toString());
                when(resultSet.getInt("attempt_count")).thenReturn(number(values[16]).intValue());
                when(resultSet.getLong("next_attempt_at")).thenReturn(number(values[17]).longValue());
                when(resultSet.getLong("version")).thenReturn(number(values[18]).longValue());
                when(resultSet.getLong("created_at")).thenReturn(number(values[19]).longValue());
                when(resultSet.getLong("updated_at")).thenReturn(number(values[20]).longValue());
                return Optional.of(mapper.map(resultSet));
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to execute test RowMapper", e);
            }
        }

        private <T> Optional<T> mapResult(PendingHandoffKey key, RowMapper<T> mapper) {
            ResultRow row = results.get(key);
            if (row == null) {
                return Optional.empty();
            }
            ResultSet resultSet = mock(ResultSet.class);
            try {
                when(resultSet.getString("ticket_id")).thenReturn(row.ticketId);
                when(resultSet.getString("ticket_status")).thenReturn(row.status);
                when(resultSet.getString("ticket_created_at")).thenReturn(row.createdAt);
                return Optional.of(mapper.map(resultSet));
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to execute test result RowMapper", e);
            }
        }

        private <T> Optional<T> mapClaim(PendingHandoffKey key, RowMapper<T> mapper) {
            ClaimRow row = claims.get(key);
            if (row == null) {
                return Optional.empty();
            }
            ResultSet resultSet = mock(ResultSet.class);
            try {
                when(resultSet.getString("owner_token")).thenReturn(row.ownerToken);
                when(resultSet.getLong("lease_until")).thenReturn(row.leaseUntil);
                return Optional.of(mapper.map(resultSet));
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to execute claim RowMapper", e);
            }
        }

        private PendingHandoffStatus statusOf(PendingHandoffKey key) {
            return PendingHandoffStatus.valueOf(rows.get(key).values[14].toString());
        }

        private static PendingHandoffKey key(Object tenantId, Object idempotencyKey) {
            return new PendingHandoffKey(tenantId.toString(), idempotencyKey.toString());
        }

        private static JdbcDuplicateKeyException duplicate() {
            return new JdbcDuplicateKeyException(
                    new SQLException("duplicate idempotency key", "23000", 1062)
            );
        }

        private static Number number(Object value) {
            return (Number) value;
        }

        private record Row(Object[] values) {
        }

        private record ResultRow(String ticketId, String status, String createdAt) {
        }

        private record ClaimRow(String ownerToken, long leaseUntil) {
        }
    }
}
