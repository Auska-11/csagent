package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewNotFoundException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseOperations;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertTrue(database.createTableSqls.stream()
                .anyMatch(sql -> sql.contains("idx_cs_pending_handoff_tenant_review")));
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
    void shouldPrioritizeJdbcStaleVersionOverIllegalStateForMarkResultUnknown() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord manual = moveToManualReview(
                store,
                command("tenant-a", "jdbc-unknown-conflict-priority", "普通状态机冲突优先级"),
                "owner-jdbc-unknown-priority"
        );

        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.markResultUnknown(
                        manual.key(), "STALE_AND_ILLEGAL", manual.version() - 1
                )
        );
        assertThrows(
                PendingHandoffStateConflictException.class,
                () -> store.markResultUnknown(
                        manual.key(), "CURRENT_BUT_ILLEGAL", manual.version()
                )
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
    void shouldExcludeAuthoritativeResultBeforeDueLimitWithStrictSqlContract() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord staleMain = store.prepare(
                command("due-stale-result", "较早的主表滞后记录")
        ).record();
        PendingHandoffRecord genuinelyDue = store.prepare(
                command("due-real", "较晚的真实待处理记录")
        ).record();
        database.setNextAttemptAt(staleMain.key(), 10L);
        database.setNextAttemptAt(genuinelyDue.key(), 20L);

        database.skipNextConfirmationStatusUpdate = true;
        store.confirm(staleMain.key(), result("AS-DUE-STALE"), staleMain.version());

        List<PendingHandoffRecord> due = store.findDue(20L, 1);

        assertEquals(PendingHandoffStatus.PREPARED, database.statusOf(staleMain.key()));
        assertEquals(List.of(genuinelyDue.outboxId()), due.stream()
                .map(PendingHandoffRecord::outboxId)
                .toList(), "权威结果必须在 LIMIT 前排除，不能饿死后续真实 due 记录");
        assertEquals(1, database.dueQuerySqls.size());
    }

    @Test
    void shouldRejectJdbcOwnerAfterLeaseHasExpired() throws InterruptedException {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord prepared = store.prepare(command("idem-jdbc-expired", "过期租约")).record();
        PendingHandoffClaim claim = store.tryClaim(
                prepared.key(), prepared.version(), "expired-owner", prepared.nextAttemptAt(), 1
        ).orElseThrow();

        Thread.sleep(20);

        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.scheduleRetry(claim, "TooLate", System.currentTimeMillis() + 1_000)
        );
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

    @Test
    void shouldPageJdbcManualReviewsByTenant() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord first = moveToManualReview(store, command("tenant-a", "review-1", "一"), "owner-1");
        PendingHandoffRecord second = moveToManualReview(store, command("tenant-a", "review-2", "二"), "owner-2");
        PendingHandoffRecord third = moveToManualReview(store, command("tenant-a", "review-3", "三"), "owner-3");
        moveToManualReview(store, command("tenant-b", "review-1", "另一个租户"), "owner-b");

        PendingHandoffReviewPage pageOne = store.findReviewPage(
                "tenant-a", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, null, 2
        );
        PendingHandoffReviewPage pageTwo = store.findReviewPage(
                "tenant-a", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, pageOne.nextCursor(), 2
        );

        assertTrue(pageOne.hasMore());
        assertEquals(2, pageOne.records().size());
        assertFalse(pageTwo.hasMore());
        assertEquals(1, pageTwo.records().size());
        List<String> ids = java.util.stream.Stream.concat(
                        pageOne.records().stream(), pageTwo.records().stream()
                )
                .map(PendingHandoffRecord::outboxId)
                .toList();
        assertEquals(3, ids.stream().distinct().count());
        assertTrue(ids.containsAll(List.of(first.outboxId(), second.outboxId(), third.outboxId())));
        assertTrue(store.findByTenantAndOutboxId("tenant-b", first.outboxId()).isEmpty());
    }

    @Test
    void shouldRejectInvalidJdbcReviewPaginationArguments() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database);

        assertThrows(
                IllegalArgumentException.class,
                () -> store.findReviewPage("   ", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, null, 10)
        );
        assertThrows(
                NullPointerException.class,
                () -> store.findReviewPage("tenant-a", null, null, 10)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> store.findReviewPage("tenant-a", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, null, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> store.findReviewPage("tenant-a", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, null, 101)
        );
    }

    @Test
    void shouldExcludeAuthoritativeResultBeforeApplyingPageLimit() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord first = moveToManualReview(
                store, command("tenant-a", "effective-1", "一"), "owner-effective-1"
        );
        PendingHandoffRecord second = moveToManualReview(
                store, command("tenant-a", "effective-2", "二"), "owner-effective-2"
        );
        PendingHandoffRecord third = moveToManualReview(
                store, command("tenant-a", "effective-3", "三"), "owner-effective-3"
        );
        PendingHandoffRecord staleMain = moveToManualReview(
                store, command("tenant-a", "effective-stale", "主表状态滞后"), "owner-stale"
        );
        database.skipNextConfirmationStatusUpdate = true;
        store.confirm(staleMain.key(), result("AS-AUTHORITATIVE"), staleMain.version());

        PendingHandoffReviewPage pageOne = store.findReviewPage(
                "tenant-a", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, null, 2
        );
        PendingHandoffReviewPage pageTwo = store.findReviewPage(
                "tenant-a", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, pageOne.nextCursor(), 2
        );
        List<String> ids = java.util.stream.Stream.concat(
                        pageOne.records().stream(), pageTwo.records().stream()
                )
                .map(PendingHandoffRecord::outboxId)
                .toList();

        assertTrue(pageOne.hasMore(), "stale 主表行不能吃掉用于判断 hasMore 的名额");
        assertEquals(3, ids.size());
        assertTrue(ids.containsAll(List.of(first.outboxId(), second.outboxId(), third.outboxId())));
        assertFalse(ids.contains(staleMain.outboxId()));

        // 结果表是成功证据：主表即使仍是 NEEDS_MANUAL_REVIEW，也必须出现在 CONFIRMED 列表。
        // 这条断言专门覆盖 JdbcPendingHandoffStore 中 EXISTS result 的另一条 SQL 分支。
        PendingHandoffReviewPage confirmedPage = store.findReviewPage(
                "tenant-a", PendingHandoffStatus.CONFIRMED, null, 10
        );
        assertFalse(confirmedPage.hasMore());
        assertEquals(1, confirmedPage.records().size());
        PendingHandoffRecord effectiveConfirmed = confirmedPage.records().get(0);
        assertEquals(staleMain.outboxId(), effectiveConfirmed.outboxId());
        assertEquals(PendingHandoffStatus.CONFIRMED, effectiveConfirmed.status());
        assertEquals(result("AS-AUTHORITATIVE"), effectiveConfirmed.confirmedResult());
    }

    @Test
    void shouldKeepManuallyClosedAuthoritativeWhenLegacyResultRowExists() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord manual = moveToManualReview(
                store,
                command("tenant-a", "closed-with-legacy-result", "关闭终态优先"),
                "owner-closed-result"
        );
        PendingHandoffRecord closed = store.closeFromManualReview(
                "tenant-a", manual.outboxId(), manual.version(), 6_000L
        );

        // 模拟历史异常留下的 MANUALLY_CLOSED + result 组合；读取围栏必须让人工关闭继续胜出。
        database.injectLegacyResult(closed.key(), result("AS-LEGACY-AFTER-CLOSE"));

        PendingHandoffRecord byKey = store.findByKey(closed.key()).orElseThrow();
        PendingHandoffRecord byOutbox = store.findByTenantAndOutboxId(
                "tenant-a", closed.outboxId()
        ).orElseThrow();
        PendingHandoffReviewPage closedPage = store.findReviewPage(
                "tenant-a", PendingHandoffStatus.MANUALLY_CLOSED, null, 10
        );
        PendingHandoffReviewPage confirmedPage = store.findReviewPage(
                "tenant-a", PendingHandoffStatus.CONFIRMED, null, 10
        );

        assertEquals(PendingHandoffStatus.MANUALLY_CLOSED, byKey.status());
        assertEquals(PendingHandoffStatus.MANUALLY_CLOSED, byOutbox.status());
        assertNull(byKey.confirmedResult());
        assertEquals(List.of(closed.outboxId()), closedPage.records().stream()
                .map(PendingHandoffRecord::outboxId)
                .toList());
        assertTrue(confirmedPage.records().isEmpty(),
                "遗留结果行不能把人工关闭记录列入 CONFIRMED 页面");
    }

    @Test
    void shouldRejectConfirmationOfClosedRecordBeforeInsertingResult() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord manual = moveToManualReview(
                store,
                command("tenant-a", "closed-rejects-result", "关闭后拒绝确认"),
                "owner-closed-confirm"
        );
        PendingHandoffRecord closed = store.closeFromManualReview(
                "tenant-a", manual.outboxId(), manual.version(), 7_000L
        );

        assertThrows(
                PendingHandoffStateConflictException.class,
                () -> store.confirm(closed.key(), result("AS-MUST-NOT-BE-SAVED"), closed.version())
        );

        assertFalse(database.results.containsKey(closed.key()),
                "终态检查必须发生在结果 INSERT 之前，不能给 CLOSED 留下结果脏行");
        assertEquals(PendingHandoffStatus.MANUALLY_CLOSED,
                store.findByKey(closed.key()).orElseThrow().status());
    }

    @Test
    void shouldApplyJdbcManualActionsAndRollbackFailedConfirmation() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord retrySource = moveToManualReview(
                store, command("tenant-a", "action-retry", "重试"), "owner-retry"
        );
        PendingHandoffRecord confirmSource = moveToManualReview(
                store, command("tenant-a", "action-confirm", "确认"), "owner-confirm"
        );
        PendingHandoffRecord closeSource = moveToManualReview(
                store, command("tenant-a", "action-close", "关闭"), "owner-close"
        );
        PendingHandoffRecord rollbackSource = moveToManualReview(
                store, command("tenant-a", "action-rollback", "事务回滚"), "owner-rollback"
        );

        PendingHandoffRecord retried = store.retryFromManualReview(
                "tenant-a", retrySource.outboxId(), retrySource.version(), 2_000L
        );
        AfterSalesTicketCreateResult result = result("AS-MANUAL-JDBC");
        PendingHandoffRecord confirmed = store.confirmFromManualReview(
                "tenant-a", confirmSource.outboxId(), result, confirmSource.version()
        );
        PendingHandoffRecord closed = store.closeFromManualReview(
                "tenant-a", closeSource.outboxId(), closeSource.version(), 3_000L
        );

        assertEquals(PendingHandoffStatus.RETRY_SCHEDULED, retried.status());
        assertEquals(retrySource.attemptCount(), retried.attemptCount());
        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals(result, confirmed.confirmedResult());
        assertEquals(confirmSource.attemptCount(), confirmed.attemptCount());
        assertEquals(PendingHandoffStatus.MANUALLY_CLOSED, closed.status());
        assertEquals(3, database.committedTransactions,
                "retry、confirm、close 都必须在各自事务内完成更新和回读");

        database.failNextManualConfirmationUpdate = true;
        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.confirmFromManualReview(
                        "tenant-a",
                        rollbackSource.outboxId(),
                        result("AS-MUST-ROLLBACK"),
                        rollbackSource.version()
                )
        );
        assertEquals(1, database.rolledBackTransactions);
        assertEquals(
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                store.findByTenantAndOutboxId("tenant-a", rollbackSource.outboxId())
                        .orElseThrow()
                        .status()
        );
        assertFalse(database.results.containsKey(rollbackSource.key()),
                "CAS 失败时成功结果 INSERT 必须随事务一起回滚");

        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.closeFromManualReview(
                        "tenant-a", retrySource.outboxId(), retrySource.version(), 4_000L
                )
        );
        assertThrows(
                PendingHandoffStateConflictException.class,
                () -> store.closeFromManualReview(
                        "tenant-a", confirmSource.outboxId(), confirmed.version(), 4_000L
                )
        );
        assertThrows(
                PendingHandoffReviewNotFoundException.class,
                () -> store.closeFromManualReview(
                        "tenant-b", closeSource.outboxId(), closeSource.version(), 4_000L
                )
        );
        assertThrows(
                PendingHandoffStateConflictException.class,
                () -> store.confirm(closed.key(), result("AS-TOO-LATE"), closed.version())
        );
        assertThrows(
                PendingHandoffStateConflictException.class,
                () -> store.markResultUnknown(closed.key(), "TooLate", closed.version())
        );
    }

    @Test
    void shouldRejectOuterClientAccessFromTransactionCallback() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> database.inTransaction(operations ->
                        database.update("CREATE TABLE escaped_transaction(id INT)"))
        );

        assertTrue(failure.getMessage().contains("transaction-bound operations"));
        assertEquals(1, database.rolledBackTransactions);
    }

    @Test
    void shouldApplyTheSameJdbcConflictRulesToAllManualActionsWithoutResultLeaks() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord manual = moveToManualReview(
                store,
                command("tenant-a", "jdbc-conflict-matrix", "冲突矩阵"),
                "owner-matrix"
        );

        // stale version 必须在写成功结果之前被拦截，尤其不能给 confirm 留下孤立结果行。
        assertAllJdbcManualActionsThrow(
                PendingHandoffVersionConflictException.class,
                store,
                "tenant-a",
                manual,
                manual.version() - 1
        );
        assertFalse(database.results.containsKey(manual.key()));

        // 错租户与真正不存在使用同一个 NotFound 语义，调用方无法据此枚举其他租户记录。
        assertAllJdbcManualActionsThrow(
                PendingHandoffReviewNotFoundException.class,
                store,
                "tenant-b",
                manual,
                manual.version()
        );
        assertFalse(database.results.containsKey(manual.key()));
        assertEquals(
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                store.findByTenantAndOutboxId("tenant-a", manual.outboxId()).orElseThrow().status()
        );

        PendingHandoffRecord prepared = store.prepare(
                command("tenant-a", "jdbc-invalid-manual-state", "尚未进入人工复核")
        ).record();
        // 冲突优先级是对外契约：非法状态与 stale version 同时存在时先报版本冲突。
        assertAllJdbcManualActionsThrow(
                PendingHandoffVersionConflictException.class,
                store,
                "tenant-a",
                prepared,
                prepared.version() - 1
        );
        assertAllJdbcManualActionsThrow(
                PendingHandoffStateConflictException.class,
                store,
                "tenant-a",
                prepared,
                prepared.version()
        );
        assertFalse(database.results.containsKey(prepared.key()));
        assertEquals(PendingHandoffStatus.PREPARED, store.findByKey(prepared.key()).orElseThrow().status());
    }

    private void assertAllJdbcManualActionsThrow(
            Class<? extends Throwable> expectedType,
            JdbcPendingHandoffStore store,
            String tenantId,
            PendingHandoffRecord source,
            long expectedVersion) {
        assertThrows(
                expectedType,
                () -> store.retryFromManualReview(
                        tenantId, source.outboxId(), expectedVersion, 5_000L
                )
        );
        assertThrows(
                expectedType,
                () -> store.confirmFromManualReview(
                        tenantId, source.outboxId(), result("AS-JDBC-CONFLICT-MATRIX"), expectedVersion
                )
        );
        assertThrows(
                expectedType,
                () -> store.closeFromManualReview(
                        tenantId, source.outboxId(), expectedVersion, 5_000L
                )
        );
    }

    private PendingHandoffRecord moveToManualReview(JdbcPendingHandoffStore store,
                                                    PendingHandoffCommand command,
                                                    String owner) {
        PendingHandoffRecord prepared = store.prepare(command).record();
        PendingHandoffClaim claim = store.tryClaim(
                prepared.key(), prepared.version(), owner, prepared.nextAttemptAt(), 1_000
        ).orElseThrow();
        return store.markNeedsManualReview(claim, "TEST_REQUIRES_REVIEW");
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
        private final List<String> dueQuerySqls = new ArrayList<>();
        private final ThreadLocal<Boolean> transactionActive =
                ThreadLocal.withInitial(() -> false);
        private final JdbcDatabaseOperations boundOperations = new JdbcDatabaseOperations() {
            @Override
            public int update(String sql, Object... args) {
                return executeUpdate(sql, args);
            }

            @Override
            public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
                return executeQueryOne(sql, mapper, args);
            }

            @Override
            public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... args) {
                return executeQueryList(sql, mapper, args);
            }
        };
        private boolean skipNextConfirmationStatusUpdate;
        private boolean failNextManualConfirmationUpdate;
        private int committedTransactions;
        private int rolledBackTransactions;

        private RecordingJdbcDatabaseClient() {
            super("memory", "", "", "", "");
        }

        @Override
        public int update(String sql, Object... args) {
            rejectUnboundTransactionAccess();
            return executeUpdate(sql, args);
        }

        private int executeUpdate(String sql, Object... args) {
            String normalized = normalizeSql(sql);
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
                    && normalized.contains("WHERE tenant_id = ? AND outbox_id = ?")) {
                return updateManualReview(normalized, args);
            }
            if (normalized.startsWith("UPDATE cs_pending_handoff")
                    && normalized.contains("status IN")) {
                return markUnknown(args);
            }
            if (normalized.startsWith("UPDATE cs_pending_handoff")
                    && (normalized.contains("status <>") || normalized.contains("status NOT IN"))) {
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
            rejectUnboundTransactionAccess();
            return executeQueryOne(sql, mapper, args);
        }

        private <T> Optional<T> executeQueryOne(String sql,
                                                RowMapper<T> mapper,
                                                Object... args) {
            if (sql.contains("WHERE tenant_id = ? AND outbox_id = ?")) {
                return mapByOutbox(args[0].toString(), args[1].toString(), mapper);
            }
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
            rejectUnboundTransactionAccess();
            return executeQueryList(sql, mapper, args);
        }

        private <T> List<T> executeQueryList(String sql,
                                             RowMapper<T> mapper,
                                             Object... args) {
            if (!sql.contains("FROM cs_pending_handoff")) {
                throw new IllegalArgumentException("Unexpected query SQL: " + sql);
            }
            if (sql.contains("ORDER BY updated_at DESC, outbox_id DESC")) {
                return queryReviewPage(sql, mapper, args);
            }
            assertDueQuerySql(sql);
            dueQuerySqls.add(sql);
            long now = number(args[4]).longValue();
            int limit = number(args[5]).intValue();
            List<T> mapped = new ArrayList<>();
            List<Map.Entry<PendingHandoffKey, Row>> dueRows = rows.entrySet().stream()
                    .filter(entry -> {
                        Row row = entry.getValue();
                        String status = row.values[14].toString();
                        return status.equals(args[0].toString())
                                || status.equals(args[1].toString())
                                || status.equals(args[2].toString())
                                || status.equals(args[3].toString());
                    })
                    .filter(entry -> number(entry.getValue().values[17]).longValue() <= now)
                    .filter(entry -> !results.containsKey(entry.getKey()))
                    .sorted((left, right) -> {
                        int byNextAttempt = Long.compare(
                                number(left.getValue().values[17]).longValue(),
                                number(right.getValue().values[17]).longValue()
                        );
                        return byNextAttempt != 0
                                ? byNextAttempt
                                : left.getValue().values[0].toString()
                                .compareTo(right.getValue().values[0].toString());
                    })
                    .limit(limit)
                    .toList();
            for (Map.Entry<PendingHandoffKey, Row> entry : dueRows) {
                ResultSet resultSet = mock(ResultSet.class);
                try {
                    when(resultSet.getString("tenant_id")).thenReturn(entry.getKey().tenantId());
                    when(resultSet.getString("idempotency_key")).thenReturn(entry.getKey().idempotencyKey());
                    mapped.add(mapper.map(resultSet));
                } catch (SQLException e) {
                    throw new IllegalStateException("Failed to map due key", e);
                }
            }
            return mapped;
        }

        private void assertDueQuerySql(String sql) {
            String normalized = normalizeSql(sql);
            requireSqlFragment(normalized,
                    "SELECT tenant_id, idempotency_key FROM cs_pending_handoff");
            requireSqlFragment(normalized, "WHERE status IN (?, ?, ?, ?)");
            requireSqlFragment(normalized, "AND next_attempt_at <= ?");
            requireSqlFragment(normalized,
                    "AND NOT EXISTS ( SELECT 1 FROM cs_pending_handoff_result AS result_row");
            requireSqlFragment(normalized,
                    "result_row.tenant_id = cs_pending_handoff.tenant_id");
            requireSqlFragment(normalized,
                    "result_row.idempotency_key = cs_pending_handoff.idempotency_key");
            requireSqlFragment(normalized, "ORDER BY next_attempt_at, outbox_id LIMIT ?");
        }

        @Override
        public <T> T inTransaction(TransactionWork<T> work) {
            if (Boolean.TRUE.equals(transactionActive.get())) {
                throw new IllegalStateException("Nested test transactions are not supported");
            }
            Map<PendingHandoffKey, Row> rowsBefore = new ConcurrentHashMap<>(rows);
            Map<PendingHandoffKey, ResultRow> resultsBefore = new ConcurrentHashMap<>(results);
            Map<PendingHandoffKey, ClaimRow> claimsBefore = new ConcurrentHashMap<>(claims);
            transactionActive.set(true);
            try {
                T result = work.execute(boundOperations);
                committedTransactions++;
                return result;
            } catch (RuntimeException | Error failure) {
                rows.clear();
                rows.putAll(rowsBefore);
                results.clear();
                results.putAll(resultsBefore);
                claims.clear();
                claims.putAll(claimsBefore);
                rolledBackTransactions++;
                throw failure;
            } finally {
                transactionActive.remove();
            }
        }

        private void rejectUnboundTransactionAccess() {
            if (Boolean.TRUE.equals(transactionActive.get())) {
                throw new IllegalStateException(
                        "Transaction callback must use transaction-bound operations"
                );
            }
        }

        private <T> List<T> queryReviewPage(String sql, RowMapper<T> mapper, Object[] args) {
            String tenantId = args[0].toString();
            String expectedStatus = args[1].toString();
            boolean hasCursor = args.length == 6;
            assertReviewQuerySql(sql, expectedStatus, hasCursor);
            long cursorUpdatedAt = hasCursor ? number(args[2]).longValue() : Long.MAX_VALUE;
            String cursorOutboxId = hasCursor ? args[4].toString() : null;
            int limit = number(args[hasCursor ? 5 : 2]).intValue();

            List<Map.Entry<PendingHandoffKey, Row>> ordered = rows.entrySet().stream()
                    .filter(entry -> entry.getKey().tenantId().equals(tenantId))
                    .filter(entry -> {
                        boolean hasConfirmedResult = results.containsKey(entry.getKey());
                        String storedStatus = entry.getValue().values[14].toString();
                        if (PendingHandoffStatus.CONFIRMED.name().equals(expectedStatus)) {
                            return PendingHandoffStatus.CONFIRMED.name().equals(storedStatus)
                                    || (hasConfirmedResult
                                    && (!sql.contains("status <> 'MANUALLY_CLOSED'")
                                    || !PendingHandoffStatus.MANUALLY_CLOSED.name().equals(storedStatus)));
                        }
                        if (PendingHandoffStatus.MANUALLY_CLOSED.name().equals(expectedStatus)
                                && !sql.contains("NOT EXISTS")) {
                            return PendingHandoffStatus.MANUALLY_CLOSED.name().equals(storedStatus);
                        }
                        return storedStatus.equals(expectedStatus) && !hasConfirmedResult;
                    })
                    .filter(entry -> {
                        if (!hasCursor) {
                            return true;
                        }
                        long updatedAt = number(entry.getValue().values[20]).longValue();
                        String outboxId = entry.getValue().values[0].toString();
                        return updatedAt < cursorUpdatedAt
                                || (updatedAt == cursorUpdatedAt && outboxId.compareTo(cursorOutboxId) < 0);
                    })
                    .sorted((left, right) -> {
                        int byTime = Long.compare(
                                number(right.getValue().values[20]).longValue(),
                                number(left.getValue().values[20]).longValue()
                        );
                        return byTime != 0
                                ? byTime
                                : right.getValue().values[0].toString()
                                .compareTo(left.getValue().values[0].toString());
                    })
                    .limit(limit)
                    .toList();

            List<T> mapped = new ArrayList<>();
            for (Map.Entry<PendingHandoffKey, Row> entry : ordered) {
                ResultSet resultSet = mock(ResultSet.class);
                try {
                    when(resultSet.getString("tenant_id")).thenReturn(entry.getKey().tenantId());
                    when(resultSet.getString("idempotency_key"))
                            .thenReturn(entry.getKey().idempotencyKey());
                    mapped.add(mapper.map(resultSet));
                } catch (SQLException e) {
                    throw new IllegalStateException("Failed to map review key", e);
                }
            }
            return mapped;
        }

        private void assertReviewQuerySql(String sql,
                                          String expectedStatus,
                                          boolean hasCursor) {
            String normalized = normalizeSql(sql);
            requireSqlFragment(normalized,
                    "SELECT tenant_id, idempotency_key FROM cs_pending_handoff");
            requireSqlFragment(normalized, "WHERE tenant_id = ? AND");
            requireSqlFragment(normalized, "ORDER BY updated_at DESC, outbox_id DESC LIMIT ?");

            if (PendingHandoffStatus.CONFIRMED.name().equals(expectedStatus)) {
                requireSqlFragment(normalized, "WHERE tenant_id = ? AND (status = ? OR (");
                requireSqlFragment(normalized,
                        "status <> 'MANUALLY_CLOSED' AND EXISTS (");
                requireResultCorrelation(normalized);
            } else if (PendingHandoffStatus.MANUALLY_CLOSED.name().equals(expectedStatus)) {
                requireSqlFragment(normalized, "WHERE tenant_id = ? AND status = ?");
                requireSqlAbsent(normalized, "NOT EXISTS");
            } else {
                requireSqlFragment(normalized,
                        "WHERE tenant_id = ? AND status = ? AND NOT EXISTS (");
                requireResultCorrelation(normalized);
            }

            String keyset = "AND (updated_at < ? OR (updated_at = ? AND outbox_id < ?))";
            if (hasCursor) {
                requireSqlFragment(normalized, keyset);
            } else {
                requireSqlAbsent(normalized, keyset);
            }
        }

        private void requireResultCorrelation(String normalizedSql) {
            requireSqlFragment(normalizedSql,
                    "result_row.tenant_id = cs_pending_handoff.tenant_id");
            requireSqlFragment(normalizedSql,
                    "result_row.idempotency_key = cs_pending_handoff.idempotency_key");
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
            if (existing == null) {
                return 0;
            }
            String currentStatus = existing.values[14].toString();
            for (int i = 5; i < args.length; i++) {
                if (currentStatus.equals(args[i].toString())) {
                    return 0;
                }
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
            ClaimRow claim = claims.get(key);
            if (existing == null
                    || number(existing.values[18]).longValue() != number(args[6]).longValue()
                    || !existing.values[14].toString().equals(args[7].toString())
                    || claim == null
                    || !key.equals(key(args[8], args[9]))
                    || !claim.ownerToken.equals(args[10].toString())
                    || claim.leaseUntil != number(args[11]).longValue()
                    || claim.leaseUntil <= number(args[12]).longValue()) {
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

        private int updateManualReview(String sql, Object[] args) {
            requireSqlFragment(sql, "UPDATE cs_pending_handoff SET status = ?");
            requireSqlFragment(sql, "WHERE tenant_id = ? AND outbox_id = ?");
            requireSqlFragment(sql, "AND version = ? AND status = ?");
            boolean confirmation = sql.contains("next_attempt_at = 0");
            if (confirmation && failNextManualConfirmationUpdate) {
                failNextManualConfirmationUpdate = false;
                return 0;
            }
            int tenantIndex = confirmation ? 3 : 4;
            int outboxIndex = confirmation ? 4 : 5;
            int versionIndex = confirmation ? 5 : 6;
            int sourceStatusIndex = confirmation ? 6 : 7;
            PendingHandoffKey key = findKeyByOutbox(
                    args[tenantIndex].toString(),
                    args[outboxIndex].toString()
            );
            if (key == null) {
                return 0;
            }
            Row existing = rows.get(key);
            if (number(existing.values[18]).longValue() != number(args[versionIndex]).longValue()
                    || !existing.values[14].toString().equals(args[sourceStatusIndex].toString())) {
                return 0;
            }
            Object[] changed = existing.values.clone();
            changed[14] = args[0];
            changed[15] = args[1];
            changed[17] = confirmation ? 0L : args[2];
            changed[18] = number(changed[18]).longValue() + 1;
            changed[20] = confirmation ? args[2] : args[3];
            rows.put(key, new Row(changed));
            return 1;
        }

        private PendingHandoffKey findKeyByOutbox(String tenantId, String outboxId) {
            return rows.entrySet().stream()
                    .filter(entry -> entry.getKey().tenantId().equals(tenantId))
                    .filter(entry -> entry.getValue().values[0].toString().equals(outboxId))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
        }

        private <T> Optional<T> mapByOutbox(String tenantId,
                                            String outboxId,
                                            RowMapper<T> mapper) {
            PendingHandoffKey key = findKeyByOutbox(tenantId, outboxId);
            if (key == null) {
                return Optional.empty();
            }
            ResultSet resultSet = mock(ResultSet.class);
            try {
                when(resultSet.getString("tenant_id")).thenReturn(key.tenantId());
                when(resultSet.getString("idempotency_key")).thenReturn(key.idempotencyKey());
                return Optional.of(mapper.map(resultSet));
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to map outbox key", e);
            }
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

        private void setNextAttemptAt(PendingHandoffKey key, long nextAttemptAt) {
            Row existing = rows.get(key);
            Object[] changed = existing.values.clone();
            changed[17] = nextAttemptAt;
            rows.put(key, new Row(changed));
        }

        private void injectLegacyResult(PendingHandoffKey key,
                                        AfterSalesTicketCreateResult result) {
            results.put(key, new ResultRow(result.ticketId(), result.status(), result.createdAt()));
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

        private static String normalizeSql(String sql) {
            return sql.trim().replaceAll("\\s+", " ");
        }

        private static void requireSqlFragment(String sql, String fragment) {
            if (!sql.contains(fragment)) {
                throw new AssertionError("SQL contract missing fragment '" + fragment + "': " + sql);
            }
        }

        private static void requireSqlAbsent(String sql, String fragment) {
            if (sql.contains(fragment)) {
                throw new AssertionError("SQL contract contains forbidden fragment '"
                        + fragment + "': " + sql);
            }
        }

        private record Row(Object[] values) {
        }

        private record ResultRow(String ticketId, String status, String createdAt) {
        }

        private record ClaimRow(String ownerToken, long leaseUntil) {
        }
    }
}
