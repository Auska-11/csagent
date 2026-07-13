package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffEvidenceSource;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualAction;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionContext;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionResult;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditEvent;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuthority;
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
import java.util.Comparator;
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
        assertTrue(database.createTableSqls.stream()
                .anyMatch(sql -> sql.contains("CREATE TABLE IF NOT EXISTS cs_pending_handoff_review_audit")
                        && sql.contains("uk_cs_pending_handoff_review_audit_transition")
                        && sql.contains("idx_cs_pending_handoff_review_audit_page")));
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
        PendingHandoffRecord closed = store.close(decisionContext(
                "tenant-a", manual, manual.version(), PendingHandoffEvidenceSource.NONE
        )).record();

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
        PendingHandoffRecord closed = store.close(decisionContext(
                "tenant-a", manual, manual.version(), PendingHandoffEvidenceSource.NONE
        )).record();

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
    void shouldApplyJdbcManualActionsAndAppendCompleteAuditEvents() {
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
        PendingHandoffManualDecisionResult retryDecision = store.retry(decisionContext(
                "tenant-a", retrySource, retrySource.version(), PendingHandoffEvidenceSource.NONE
        ));
        AfterSalesTicketCreateResult result = result("AS-MANUAL-JDBC");
        PendingHandoffManualDecisionResult confirmDecision = store.confirm(decisionContext(
                "tenant-a",
                confirmSource,
                confirmSource.version(),
                PendingHandoffEvidenceSource.ADMIN_PROVIDED
        ), result);
        PendingHandoffManualDecisionResult closeDecision = store.close(decisionContext(
                "tenant-a", closeSource, closeSource.version(), PendingHandoffEvidenceSource.NONE
        ));

        PendingHandoffRecord retried = retryDecision.record();
        PendingHandoffRecord confirmed = confirmDecision.record();
        PendingHandoffRecord closed = closeDecision.record();

        assertEquals(PendingHandoffStatus.RETRY_SCHEDULED, retried.status());
        assertEquals(retrySource.attemptCount(), retried.attemptCount());
        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals(result, confirmed.confirmedResult());
        assertEquals(confirmSource.attemptCount(), confirmed.attemptCount());
        assertEquals(PendingHandoffStatus.MANUALLY_CLOSED, closed.status());
        assertManualAudit(
                retryDecision,
                retrySource,
                PendingHandoffManualAction.RETRY,
                PendingHandoffEvidenceSource.NONE
        );
        assertManualAudit(
                confirmDecision,
                confirmSource,
                PendingHandoffManualAction.CONFIRM,
                PendingHandoffEvidenceSource.ADMIN_PROVIDED
        );
        assertManualAudit(
                closeDecision,
                closeSource,
                PendingHandoffManualAction.CLOSE,
                PendingHandoffEvidenceSource.NONE
        );
        assertEquals(3, database.audits.size());
        assertEquals(3, database.committedTransactions,
                "retry、confirm、close 都必须在各自事务内完成更新、审计追加和回读");

        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.close(decisionContext(
                        "tenant-a",
                        retrySource,
                        retrySource.version(),
                        PendingHandoffEvidenceSource.NONE
                ))
        );
        assertThrows(
                PendingHandoffStateConflictException.class,
                () -> store.close(decisionContext(
                        "tenant-a",
                        confirmed,
                        confirmed.version(),
                        PendingHandoffEvidenceSource.NONE
                ))
        );
        assertThrows(
                PendingHandoffReviewNotFoundException.class,
                () -> store.close(decisionContext(
                        "tenant-b",
                        closed,
                        closed.version(),
                        PendingHandoffEvidenceSource.NONE
                ))
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
    void shouldRollbackMainResultAndAuditTogetherWhenAuditInsertFails() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord rollbackSource = moveToManualReview(
                store, command("tenant-a", "action-rollback", "事务回滚"), "owner-rollback"
        );

        // CONFIRM 在审计 INSERT 前已经写入 result 并更新主表。这里从最后一步注入故障，
        // 可以同时证明三个容器都由同一个数据库事务保护，而不只是“恰好没有写 result”。
        database.failNextAuditInsert = true;
        assertThrows(
                IllegalStateException.class,
                () -> store.confirm(decisionContext(
                        "tenant-a",
                        rollbackSource,
                        rollbackSource.version(),
                        PendingHandoffEvidenceSource.ADMIN_PROVIDED
                ), result("AS-MUST-ROLLBACK"))
        );

        assertEquals(1, database.rolledBackTransactions);
        assertEquals(
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                store.findByTenantAndOutboxId("tenant-a", rollbackSource.outboxId())
                        .orElseThrow()
                        .status()
        );
        assertFalse(database.results.containsKey(rollbackSource.key()),
                "审计失败时成功结果 INSERT 必须随事务一起回滚");
        assertTrue(database.audits.isEmpty(),
                "失败事务既不能留下业务变更，也不能留下声称操作成功的审计事件");

        // 原有 CAS 失败注入仍保留，避免未来调整 SQL 顺序后退化成半成功。
        database.failNextManualConfirmationUpdate = true;
        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.confirm(decisionContext(
                        "tenant-a",
                        rollbackSource,
                        rollbackSource.version(),
                        PendingHandoffEvidenceSource.ADMIN_PROVIDED
                ), result("AS-MUST-ROLLBACK-CAS"))
        );
        assertEquals(2, database.rolledBackTransactions);
        assertFalse(database.results.containsKey(rollbackSource.key()));
        assertTrue(database.audits.isEmpty());
    }

    @Test
    void shouldQueryJdbcAuditTimelineInsideTenantBoundary() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord tenantA = moveToManualReview(
                store, command("tenant-a", "audit-tenant-a", "A 租户审计"), "owner-audit-a"
        );
        PendingHandoffRecord tenantB = moveToManualReview(
                store, command("tenant-b", "audit-tenant-b", "B 租户审计"), "owner-audit-b"
        );
        PendingHandoffManualDecisionResult tenantADecision = store.close(decisionContext(
                "tenant-a", tenantA, tenantA.version(), PendingHandoffEvidenceSource.NONE
        ));
        store.close(decisionContext(
                "tenant-b", tenantB, tenantB.version(), PendingHandoffEvidenceSource.NONE
        ));

        PendingHandoffReviewAuditPage ownPage = store.findAuditPage(
                "tenant-a", tenantA.outboxId(), null, 10
        );
        PendingHandoffReviewAuditPage crossTenantPage = store.findAuditPage(
                "tenant-b", tenantA.outboxId(), null, 10
        );

        assertFalse(ownPage.hasMore());
        assertEquals(List.of(tenantADecision.auditEvent()), ownPage.events());
        assertTrue(crossTenantPage.events().isEmpty(),
                "只知道 outboxId 不能跨租户读取管理员身份和决定备注");
        assertTrue(store.findAuditPage("tenant-a", "missing-outbox", null, 10)
                .events()
                .isEmpty());
    }

    @Test
    void shouldPageJdbcAuditsByOccurredAtAndAuditIdWithoutTieDuplicates() {
        RecordingJdbcDatabaseClient database = new RecordingJdbcDatabaseClient();
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        String outboxId = "outbox-audit-keyset";
        long sameOccurredAt = 9_000L;

        // 毫秒时间戳可能相同，所以游标必须同时携带 auditId。这里故意逆序注入，
        // 证明结果来自 SQL 排序而不是 Map 的偶然迭代顺序。
        database.injectClosedAudit("audit-a", "tenant-a", outboxId, 3, sameOccurredAt);
        database.injectClosedAudit("audit-c", "tenant-a", outboxId, 1, sameOccurredAt);
        database.injectClosedAudit("audit-b", "tenant-a", outboxId, 2, sameOccurredAt);
        database.injectClosedAudit("audit-z-other", "tenant-b", outboxId, 1, sameOccurredAt);

        PendingHandoffReviewAuditPage first = store.findAuditPage(
                "tenant-a", outboxId, null, 2
        );
        PendingHandoffReviewAuditPage second = store.findAuditPage(
                "tenant-a", outboxId, first.nextCursor(), 2
        );

        assertTrue(first.hasMore());
        assertEquals(List.of("audit-c", "audit-b"), first.events().stream()
                .map(PendingHandoffReviewAuditEvent::auditId)
                .toList());
        assertEquals(new PendingHandoffReviewAuditCursor(sameOccurredAt, "audit-b"),
                first.nextCursor());
        assertFalse(second.hasMore());
        assertEquals(List.of("audit-a"), second.events().stream()
                .map(PendingHandoffReviewAuditEvent::auditId)
                .toList());
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
                () -> store.retry(decisionContext(
                        tenantId,
                        source,
                        expectedVersion,
                        PendingHandoffEvidenceSource.NONE
                ))
        );
        assertThrows(
                expectedType,
                () -> store.confirm(decisionContext(
                        tenantId,
                        source,
                        expectedVersion,
                        PendingHandoffEvidenceSource.ADMIN_PROVIDED
                ), result("AS-JDBC-CONFLICT-MATRIX"))
        );
        assertThrows(
                expectedType,
                () -> store.close(decisionContext(
                        tenantId,
                        source,
                        expectedVersion,
                        PendingHandoffEvidenceSource.NONE
                ))
        );
    }

    private PendingHandoffManualDecisionContext decisionContext(
            String tenantId,
            PendingHandoffRecord source,
            long expectedVersion,
            PendingHandoffEvidenceSource evidenceSource) {
        return new PendingHandoffManualDecisionContext(
                tenantId,
                source.outboxId(),
                expectedVersion,
                "admin-jdbc",
                PendingHandoffReviewAuthority.ADMIN,
                "TEST_MANUAL_DECISION",
                "测试人工处置备注",
                evidenceSource
        );
    }

    private void assertManualAudit(PendingHandoffManualDecisionResult decision,
                                   PendingHandoffRecord source,
                                   PendingHandoffManualAction action,
                                   PendingHandoffEvidenceSource evidenceSource) {
        PendingHandoffRecord record = decision.record();
        PendingHandoffReviewAuditEvent audit = decision.auditEvent();

        assertEquals(source.tenantId(), audit.tenantId());
        assertEquals(source.outboxId(), audit.outboxId());
        assertEquals(action, audit.action());
        assertEquals("admin-jdbc", audit.actorId());
        assertEquals(PendingHandoffReviewAuthority.ADMIN, audit.authorityUsed());
        assertEquals("TEST_MANUAL_DECISION", audit.reasonCode());
        assertEquals("测试人工处置备注", audit.decisionNote());
        assertEquals(evidenceSource, audit.evidenceSource());
        assertEquals(PendingHandoffStatus.NEEDS_MANUAL_REVIEW, audit.fromStatus());
        assertEquals(record.status(), audit.toStatus());
        assertEquals(source.version(), audit.fromVersion());
        assertEquals(record.version(), audit.toVersion());
        assertEquals(source.failureType(), audit.reviewFailureType());
        assertEquals(source.attemptCount(), audit.attemptCountAtDecision());
        assertEquals(record.attemptCount(), audit.attemptCountAtDecision());
        assertTrue(audit.occurredAt() >= source.updatedAt());
        if (action == PendingHandoffManualAction.RETRY) {
            assertEquals(record.nextAttemptAt(), audit.scheduledAt());
        } else {
            assertNull(audit.scheduledAt());
        }
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
        private final Map<String, AuditRow> audits = new ConcurrentHashMap<>();
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
        private boolean failNextAuditInsert;
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
            // 审计表、结果表和 claim 表都共享主表前缀，必须先识别更长的名字，
            // 否则 17 列审计 INSERT 会被误当成 21 列主记录。
            if (normalized.startsWith("INSERT INTO cs_pending_handoff_review_audit")) {
                if (failNextAuditInsert) {
                    failNextAuditInsert = false;
                    throw new IllegalStateException("Injected audit insert failure");
                }
                if (args.length != 17) {
                    throw new AssertionError("Audit INSERT must provide all 17 columns");
                }
                AuditRow candidate = AuditRow.from(args);
                if (audits.putIfAbsent(candidate.auditId, candidate) != null) {
                    throw duplicate();
                }
                return 1;
            }
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
            // 和 INSERT 一样，审计表名必须先于主表匹配。
            if (sql.contains("FROM cs_pending_handoff_review_audit")) {
                return queryAuditPage(sql, mapper, args);
            }
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
            Map<String, AuditRow> auditsBefore = new ConcurrentHashMap<>(audits);
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
                audits.clear();
                audits.putAll(auditsBefore);
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

        private <T> List<T> queryAuditPage(String sql, RowMapper<T> mapper, Object[] args) {
            boolean hasCursor = args.length == 6;
            assertAuditQuerySql(sql, hasCursor);
            String tenantId = args[0].toString();
            String outboxId = args[1].toString();
            long cursorOccurredAt = hasCursor ? number(args[2]).longValue() : Long.MAX_VALUE;
            String cursorAuditId = hasCursor ? args[4].toString() : null;
            int limit = number(args[hasCursor ? 5 : 2]).intValue();

            List<AuditRow> ordered = audits.values().stream()
                    .filter(row -> row.tenantId.equals(tenantId))
                    .filter(row -> row.outboxId.equals(outboxId))
                    .filter(row -> !hasCursor
                            || row.occurredAt < cursorOccurredAt
                            || (row.occurredAt == cursorOccurredAt
                            && row.auditId.compareTo(cursorAuditId) < 0))
                    .sorted(Comparator.comparingLong(AuditRow::occurredAt)
                            .reversed()
                            .thenComparing(AuditRow::auditId, Comparator.reverseOrder()))
                    .limit(limit)
                    .toList();

            List<T> mapped = new ArrayList<>();
            for (AuditRow row : ordered) {
                mapped.add(mapAudit(row, mapper));
            }
            return mapped;
        }

        private void assertAuditQuerySql(String sql, boolean hasCursor) {
            String normalized = normalizeSql(sql);
            requireSqlFragment(normalized,
                    "SELECT audit_id, tenant_id, outbox_id, action, actor_id,");
            requireSqlFragment(normalized,
                    "review_failure_type, attempt_count_at_decision, scheduled_at, occurred_at");
            requireSqlFragment(normalized,
                    "FROM cs_pending_handoff_review_audit WHERE tenant_id = ? AND outbox_id = ?");
            requireSqlFragment(normalized,
                    "ORDER BY occurred_at DESC, audit_id DESC LIMIT ?");
            String keyset = "AND (occurred_at < ? OR (occurred_at = ? AND audit_id < ?))";
            if (hasCursor) {
                requireSqlFragment(normalized, keyset);
            } else {
                requireSqlAbsent(normalized, keyset);
            }
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
            boolean confirmation = PendingHandoffStatus.CONFIRMED.name().equals(args[0].toString());
            if (confirmation && failNextManualConfirmationUpdate) {
                failNextManualConfirmationUpdate = false;
                return 0;
            }
            PendingHandoffKey key = findKeyByOutbox(
                    args[4].toString(),
                    args[5].toString()
            );
            if (key == null) {
                return 0;
            }
            Row existing = rows.get(key);
            if (number(existing.values[18]).longValue() != number(args[6]).longValue()
                    || !existing.values[14].toString().equals(args[7].toString())) {
                return 0;
            }
            Object[] changed = existing.values.clone();
            changed[14] = args[0];
            changed[15] = args[1];
            changed[17] = args[2];
            changed[18] = number(changed[18]).longValue() + 1;
            changed[20] = args[3];
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

        private <T> T mapAudit(AuditRow row, RowMapper<T> mapper) {
            ResultSet resultSet = mock(ResultSet.class);
            try {
                when(resultSet.getString("audit_id")).thenReturn(row.auditId);
                when(resultSet.getString("tenant_id")).thenReturn(row.tenantId);
                when(resultSet.getString("outbox_id")).thenReturn(row.outboxId);
                when(resultSet.getString("action")).thenReturn(row.action);
                when(resultSet.getString("actor_id")).thenReturn(row.actorId);
                when(resultSet.getString("authority_used")).thenReturn(row.authorityUsed);
                when(resultSet.getString("reason_code")).thenReturn(row.reasonCode);
                when(resultSet.getString("decision_note")).thenReturn(row.decisionNote);
                when(resultSet.getString("evidence_source")).thenReturn(row.evidenceSource);
                when(resultSet.getString("from_status")).thenReturn(row.fromStatus);
                when(resultSet.getString("to_status")).thenReturn(row.toStatus);
                when(resultSet.getLong("from_version")).thenReturn(row.fromVersion);
                when(resultSet.getLong("to_version")).thenReturn(row.toVersion);
                when(resultSet.getString("review_failure_type"))
                        .thenReturn(row.reviewFailureType);
                when(resultSet.getInt("attempt_count_at_decision"))
                        .thenReturn(row.attemptCountAtDecision);
                when(resultSet.getObject("scheduled_at")).thenReturn(row.scheduledAt);
                when(resultSet.getLong("occurred_at")).thenReturn(row.occurredAt);
                return mapper.map(resultSet);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to execute audit RowMapper", e);
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

        private void injectClosedAudit(String auditId,
                                       String tenantId,
                                       String outboxId,
                                       long fromVersion,
                                       long occurredAt) {
            audits.put(auditId, new AuditRow(
                    auditId,
                    tenantId,
                    outboxId,
                    PendingHandoffManualAction.CLOSE.name(),
                    "admin-injected",
                    PendingHandoffReviewAuthority.ADMIN.name(),
                    "TEST_HISTORY",
                    "历史人工关闭",
                    PendingHandoffEvidenceSource.NONE.name(),
                    PendingHandoffStatus.NEEDS_MANUAL_REVIEW.name(),
                    PendingHandoffStatus.MANUALLY_CLOSED.name(),
                    fromVersion,
                    fromVersion + 1,
                    "TEST_REQUIRES_REVIEW",
                    1,
                    null,
                    occurredAt
            ));
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

        /** 与审计 INSERT 的 17 列一一对应，避免测试替身悄悄漏存后来新增的审计字段。 */
        private record AuditRow(
                String auditId,
                String tenantId,
                String outboxId,
                String action,
                String actorId,
                String authorityUsed,
                String reasonCode,
                String decisionNote,
                String evidenceSource,
                String fromStatus,
                String toStatus,
                long fromVersion,
                long toVersion,
                String reviewFailureType,
                int attemptCountAtDecision,
                Long scheduledAt,
                long occurredAt) {

            private static AuditRow from(Object[] args) {
                return new AuditRow(
                        args[0].toString(),
                        args[1].toString(),
                        args[2].toString(),
                        args[3].toString(),
                        args[4].toString(),
                        args[5].toString(),
                        args[6].toString(),
                        args[7].toString(),
                        args[8].toString(),
                        args[9].toString(),
                        args[10].toString(),
                        number(args[11]).longValue(),
                        number(args[12]).longValue(),
                        args[13].toString(),
                        number(args[14]).intValue(),
                        args[15] == null ? null : number(args[15]).longValue(),
                        number(args[16]).longValue()
                );
            }
        }

        private record ClaimRow(String ownerToken, long leaseUntil) {
        }
    }
}
