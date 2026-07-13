package com.smartcs.smart_cs_agent.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.core.CapabilityResultCodec;
import com.smartcs.smart_cs_agent.capability.idempotency.jdbc.JdbcCapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.handoff.outbox.JdbcPendingHandoffStore;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffClaim;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffKey;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffVersionConflictException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffEvidenceSource;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualAction;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionContext;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuthority;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import com.smartcs.smart_cs_agent.session.core.ConversationStateCodec;
import com.smartcs.smart_cs_agent.session.jdbc.JdbcSessionStore;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL 验收测试。
 *
 * <p>类名以 IT 结尾，因此默认 mvn test 不会执行；启动 Compose MySQL 后显式运行：</p>
 * <pre>mvn -Dtest=RealMySqlPersistenceIT test</pre>
 *
 * <p>它不使用数据库替身，直接验证 MySQL 唯一键、CAS、并发等待、租约接管和查询超时。</p>
 */
class RealMySqlPersistenceIT {

    @Test
    void shouldVerifySessionCasIdempotencyLeaseAndQueryTimeoutAgainstRealMysql() throws Exception {
        JdbcDatabaseClient database = databaseClient(1);
        String suffix = UUID.randomUUID().toString();

        verifySessionCas(database, suffix);
        verifyIdempotencyConcurrency(database, suffix);
        verifyOutboxLease(database, suffix);
        verifyDueResultExclusionBeforeLimit(database, suffix);
        verifyManualConfirmationConcurrency(database, suffix);
        verifyManualCloseAndLateConfirmationOrdering(database, suffix);
        verifyAuditFailureRollsBackWholeDecision(database, suffix);
        verifyAuditKeysetTieBreak(database, suffix);
        verifyQueryTimeout(database);
    }

    private void verifySessionCas(JdbcDatabaseClient database, String suffix) {
        ConversationStateCodec codec = new ConversationStateCodec(new ObjectMapper());
        JdbcSessionStore first = new JdbcSessionStore(database, codec);
        JdbcSessionStore second = new JdbcSessionStore(database, codec);
        ConversationKey key = ConversationKey.defaultTenant("USER-REAL", "real-session-" + suffix);
        ConversationState state = new ConversationState("真实 MySQL", "USER-REAL");

        SessionRecord created = first.save(key, 0, state.toPersistenceSnapshot());
        SessionRecord restored = second.load(key).orElseThrow();
        assertEquals(1, restored.version());
        assertEquals("真实 MySQL", restored.snapshot().message());

        state.startNewTurn("CAS 更新");
        SessionRecord updated = second.save(key, restored.version(), state.toPersistenceSnapshot());
        assertEquals(2, updated.version());
        assertThrows(
                SessionVersionConflictException.class,
                () -> first.save(key, created.version(), state.toPersistenceSnapshot())
        );
        assertTrue(second.delete(key, updated.version()));
    }

    private void verifyIdempotencyConcurrency(JdbcDatabaseClient database, String suffix) throws Exception {
        CapabilityResultCodec codec = new CapabilityResultCodec(new ObjectMapper());
        JdbcCapabilityIdempotencyStore first = new JdbcCapabilityIdempotencyStore(
                database, codec, 5_000, 5_000, 20
        );
        JdbcCapabilityIdempotencyStore second = new JdbcCapabilityIdempotencyStore(
                database, codec, 5_000, 5_000, 20
        );
        String key = "real-mysql-idempotency-" + suffix;
        AtomicInteger providerCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CapabilityResult<?>> owner = executor.submit(() -> first.execute(
                    key,
                    "same-fingerprint",
                    () -> {
                        providerCalls.incrementAndGet();
                        entered.countDown();
                        await(release);
                        return CapabilityResult.success(
                                "handoff.request", "real-mysql", "ok", Map.of("ticket", "AS-REAL")
                        );
                    }
            ));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<CapabilityResult<?>> waiter = executor.submit(() -> second.execute(
                    key,
                    "same-fingerprint",
                    () -> {
                        providerCalls.incrementAndGet();
                        throw new IllegalStateException("second provider must not execute");
                    }
            ));
            release.countDown();

            assertTrue(owner.get(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(waiter.get(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, providerCalls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void verifyOutboxLease(JdbcDatabaseClient database, String suffix) {
        JdbcPendingHandoffStore first = new JdbcPendingHandoffStore(database, 1);
        JdbcPendingHandoffStore second = new JdbcPendingHandoffStore(database, 1);
        String key = "real-outbox-" + suffix;
        String tenantId = "tenant-real-" + suffix;
        String otherTenantId = tenantId + "-other";
        PendingHandoffRecord prepared = first.prepare(new PendingHandoffCommand(
                tenantId,
                "USER-REAL",
                "real-conversation-" + suffix,
                "COMPLAINT",
                "真实数据库租约",
                new AfterSalesTicketCreateRequest(
                        "真实 MySQL 工单",
                        "验证 Outbox 租约",
                        "USER-REAL",
                        "general",
                        "complaint",
                        "HIGH",
                        key
                )
        )).record();
        // 使用同一个裸幂等键再写入另一个租户，真实验证数据库复合唯一键、结果表和租约表都已隔离。
        PendingHandoffRecord otherTenant = second.prepare(new PendingHandoffCommand(
                otherTenantId,
                "USER-REAL",
                "real-conversation-other-" + suffix,
                "COMPLAINT",
                "另一个租户的同名幂等请求",
                new AfterSalesTicketCreateRequest(
                        "真实 MySQL 工单 B",
                        "验证跨租户复合键",
                        "USER-REAL",
                        "general",
                        "complaint",
                        "HIGH",
                        key
                )
        )).record();
        long due = Math.max(prepared.nextAttemptAt(), otherTenant.nextAttemptAt());

        PendingHandoffClaim owner = first.tryClaim(
                prepared.key(), prepared.version(), "real-owner-1", due, 5_000
        ).orElseThrow();
        PendingHandoffClaim otherOwner = second.tryClaim(
                otherTenant.key(), otherTenant.version(), "real-owner-other", due, 5_000
        ).orElseThrow();
        PendingHandoffRecord otherConfirmed = second.confirm(
                otherTenant.key(),
                new AfterSalesTicketCreateResult("AS-REAL-OTHER", "PENDING", "2026-07-13T00:00:00Z"),
                otherOwner.record().version()
        );
        second.releaseClaim(otherOwner);
        assertEquals("AS-REAL-OTHER", otherConfirmed.confirmedResult().ticketId());

        assertTrue(second.tryClaim(
                prepared.key(), owner.record().version(), "real-owner-2", due + 2_500, 5_000
        ).isEmpty());
        PendingHandoffClaim takeover = second.tryClaim(
                prepared.key(), owner.record().version(), "real-owner-2", due + 5_000, 5_000
        ).orElseThrow();
        PendingHandoffRecord manual = second.markNeedsManualReview(takeover, "RealMysqlVerified");
        assertEquals("NEEDS_MANUAL_REVIEW", manual.status().name());
        assertEquals("CONFIRMED", second.findByKey(otherTenant.key()).orElseThrow().status().name());

        PendingHandoffRecord closed = second.close(manualContext(
                manual,
                PendingHandoffEvidenceSource.NONE,
                "LEASE_EXPIRED_NO_ACTION",
                "真实 MySQL 租约测试完成后关闭"
        )).record();
        assertEquals(PendingHandoffStatus.MANUALLY_CLOSED, closed.status());
        PendingHandoffReviewAuditPage closeAudit = second.findAuditPage(
                tenantId, manual.outboxId(), null, 10
        );
        assertEquals(1, closeAudit.events().size());
        assertEquals(PendingHandoffManualAction.CLOSE, closeAudit.events().get(0).action());
        assertTrue(second.findAuditPage(otherTenantId, manual.outboxId(), null, 10).events().isEmpty());

        PendingHandoffRecord retrySource = prepareManualReview(
                first, tenantId, "real-review-retry-" + suffix, "real-review-owner-1"
        );
        PendingHandoffRecord confirmSource = prepareManualReview(
                first, tenantId, "real-review-confirm-" + suffix, "real-review-owner-2"
        );
        PendingHandoffRecord closeSource = prepareManualReview(
                first, tenantId, "real-review-close-" + suffix, "real-review-owner-3"
        );
        PendingHandoffRecord staleMain = prepareManualReview(
                first, tenantId, "real-review-stale-" + suffix, "real-review-owner-stale"
        );
        // 故障注入：模拟“结果表已提交、主表仍停留 NEEDS_MANUAL_REVIEW”的崩溃恢复态。
        database.update("""
                INSERT INTO cs_pending_handoff_result(
                    tenant_id, idempotency_key, ticket_id, ticket_status, ticket_created_at, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                staleMain.tenantId(),
                staleMain.request().idempotencyKey(),
                "AS-STALE-MAIN",
                "PENDING",
                "2026-07-13T00:00:00Z",
                System.currentTimeMillis());
        PendingHandoffReviewPage firstPage = first.findReviewPage(
                tenantId, PendingHandoffStatus.NEEDS_MANUAL_REVIEW, null, 2
        );
        PendingHandoffReviewPage secondPage = first.findReviewPage(
                tenantId,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                firstPage.nextCursor(),
                2
        );
        assertTrue(firstPage.hasMore());
        assertEquals(2, firstPage.records().size());
        assertEquals(1, secondPage.records().size());
        assertTrue(!secondPage.hasMore());
        java.util.HashSet<String> pagedIds = new java.util.HashSet<>();
        firstPage.records().forEach(record -> pagedIds.add(record.outboxId()));
        secondPage.records().forEach(record -> pagedIds.add(record.outboxId()));
        assertEquals(
                Set.of(retrySource.outboxId(), confirmSource.outboxId(), closeSource.outboxId()),
                pagedIds
        );
        assertTrue(!pagedIds.contains(staleMain.outboxId()));

        PendingHandoffRecord retried = first.retry(manualContext(
                retrySource,
                PendingHandoffEvidenceSource.NONE,
                "RETRY_APPROVED",
                "管理员批准立即重新排队"
        )).record();
        PendingHandoffRecord manuallyConfirmed = first.confirm(
                manualContext(
                        confirmSource,
                        PendingHandoffEvidenceSource.ADMIN_PROVIDED,
                        "REMOTE_TICKET_VERIFIED",
                        "已在受信任售后后台核对"
                ),
                new AfterSalesTicketCreateResult(
                        "AS-REAL-MANUAL", "PENDING", "2026-07-13T00:00:01Z"
                )
        ).record();
        PendingHandoffRecord reviewClosed = first.close(manualContext(
                closeSource,
                PendingHandoffEvidenceSource.NONE,
                "NO_TICKET_REQUIRED",
                "业务确认无需继续创建工单"
        )).record();
        assertEquals(PendingHandoffStatus.RETRY_SCHEDULED, retried.status());
        assertEquals(retrySource.attemptCount(), retried.attemptCount());
        assertEquals(PendingHandoffStatus.CONFIRMED, manuallyConfirmed.status());
        assertEquals(confirmSource.attemptCount(), manuallyConfirmed.attemptCount());
        assertEquals(PendingHandoffStatus.MANUALLY_CLOSED, reviewClosed.status());
        assertEquals(PendingHandoffManualAction.RETRY,
                first.findAuditPage(tenantId, retrySource.outboxId(), null, 10)
                        .events().get(0).action());
        assertEquals(PendingHandoffManualAction.CONFIRM,
                first.findAuditPage(tenantId, confirmSource.outboxId(), null, 10)
                        .events().get(0).action());
        assertEquals(PendingHandoffManualAction.CLOSE,
                first.findAuditPage(tenantId, closeSource.outboxId(), null, 10)
                        .events().get(0).action());
        assertTrue(first.findByTenantAndOutboxId(otherTenantId, retrySource.outboxId()).isEmpty());
    }

    private PendingHandoffRecord prepareManualReview(JdbcPendingHandoffStore store,
                                                     String tenantId,
                                                     String key,
                                                     String owner) {
        PendingHandoffRecord prepared = store.prepare(new PendingHandoffCommand(
                tenantId,
                "USER-REAL",
                "manual-conversation-" + key,
                "COMPLAINT",
                "真实 MySQL 人工复核",
                new AfterSalesTicketCreateRequest(
                        "真实 MySQL 人工复核",
                        "验证人工状态机",
                        "USER-REAL",
                        "general",
                        "complaint",
                        "HIGH",
                        key
                )
        )).record();
        PendingHandoffClaim claim = store.tryClaim(
                prepared.key(), prepared.version(), owner, prepared.nextAttemptAt(), 5_000
        ).orElseThrow();
        return store.markNeedsManualReview(claim, "REAL_MYSQL_REVIEW");
    }

    private void verifyDueResultExclusionBeforeLimit(JdbcDatabaseClient database,
                                                     String suffix) {
        String tenantPrefix = "tenant-due-limit-it-";
        String tenantId = tenantPrefix + suffix;
        // IT 使用外部持久化 MySQL，先清理上次失败可能遗留的同类测试数据。
        database.update("DELETE FROM cs_pending_handoff_result WHERE tenant_id LIKE ?", tenantPrefix + "%");
        database.update("DELETE FROM cs_pending_handoff_claim WHERE tenant_id LIKE ?", tenantPrefix + "%");
        database.update("DELETE FROM cs_pending_handoff WHERE tenant_id LIKE ?", tenantPrefix + "%");

        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        PendingHandoffRecord staleMain = store.prepare(pendingCommand(
                tenantId,
                "real-due-stale-" + suffix,
                "较早的主表滞后记录"
        )).record();
        PendingHandoffRecord genuinelyDue = store.prepare(pendingCommand(
                tenantId,
                "real-due-live-" + suffix,
                "较晚的真实待处理记录"
        )).record();
        try {
            database.update("""
                    UPDATE cs_pending_handoff
                    SET next_attempt_at = ?
                    WHERE tenant_id = ? AND idempotency_key = ?
                    """, 0L, staleMain.tenantId(), staleMain.request().idempotencyKey());
            database.update("""
                    UPDATE cs_pending_handoff
                    SET next_attempt_at = ?
                    WHERE tenant_id = ? AND idempotency_key = ?
                    """, 1L, genuinelyDue.tenantId(), genuinelyDue.request().idempotencyKey());
            database.update("""
                    INSERT INTO cs_pending_handoff_result(
                        tenant_id, idempotency_key, ticket_id,
                        ticket_status, ticket_created_at, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    staleMain.tenantId(),
                    staleMain.request().idempotencyKey(),
                    "AS-DUE-STALE-REAL",
                    "PENDING",
                    "2026-07-13T00:00:03Z",
                    System.currentTimeMillis());

            List<PendingHandoffRecord> due = store.findDue(1L, 1);

            assertEquals(List.of(genuinelyDue.outboxId()), due.stream()
                    .map(PendingHandoffRecord::outboxId)
                    .toList(), "较早的权威结果必须在 LIMIT 前排除");
        } finally {
            database.update("DELETE FROM cs_pending_handoff_result WHERE tenant_id = ?", tenantId);
            database.update("DELETE FROM cs_pending_handoff_claim WHERE tenant_id = ?", tenantId);
            database.update("DELETE FROM cs_pending_handoff WHERE tenant_id = ?", tenantId);
        }
    }

    private PendingHandoffCommand pendingCommand(String tenantId,
                                                 String idempotencyKey,
                                                 String summary) {
        return new PendingHandoffCommand(
                tenantId,
                "USER-REAL",
                "due-conversation-" + idempotencyKey,
                "COMPLAINT",
                summary,
                new AfterSalesTicketCreateRequest(
                        "真实 MySQL due 过滤",
                        "验证权威结果在 LIMIT 之前排除",
                        "USER-REAL",
                        "general",
                        "complaint",
                        "HIGH",
                        idempotencyKey
                )
        );
    }

    private void verifyManualConfirmationConcurrency(JdbcDatabaseClient database,
                                                     String suffix) throws Exception {
        JdbcPendingHandoffStore first = new JdbcPendingHandoffStore(database, 1);
        JdbcPendingHandoffStore second = new JdbcPendingHandoffStore(database, 1);
        String tenantId = "tenant-concurrent-" + suffix;
        PendingHandoffRecord manual = prepareManualReview(
                first,
                tenantId,
                "real-concurrent-confirm-" + suffix,
                "concurrent-prepare-owner"
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> left = executor.submit(() -> confirmConcurrently(
                    first, manual, result("AS-CONCURRENT-A"), start
            ));
            Future<String> right = executor.submit(() -> confirmConcurrently(
                    second, manual, result("AS-CONCURRENT-B"), start
            ));
            start.countDown();

            assertEquals(
                    Set.of("SUCCESS", "VERSION_CONFLICT"),
                    Set.of(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS))
            );
            PendingHandoffRecord finalRecord = first.findByTenantAndOutboxId(
                    tenantId, manual.outboxId()
            ).orElseThrow();
            assertEquals(PendingHandoffStatus.CONFIRMED, finalRecord.status());
            assertTrue(Set.of("AS-CONCURRENT-A", "AS-CONCURRENT-B")
                    .contains(finalRecord.confirmedResult().ticketId()));
            // 两个管理员使用同一个 expectedVersion 竞争时，行锁和 CAS 只能允许一个事务提交；
            // 因而业务状态、成功结果和审计历史都必须恰好只有一份。
            assertEquals(1, first.findAuditPage(tenantId, manual.outboxId(), null, 10).events().size());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private String confirmConcurrently(JdbcPendingHandoffStore store,
                                       PendingHandoffRecord manual,
                                       AfterSalesTicketCreateResult result,
                                       CountDownLatch start) {
        await(start);
        try {
            store.confirm(
                    manualContext(
                            manual,
                            PendingHandoffEvidenceSource.ADMIN_PROVIDED,
                            "CONCURRENT_REMOTE_VERIFICATION",
                            "并发确认测试"
                    ),
                    result
            );
            return "SUCCESS";
        } catch (PendingHandoffVersionConflictException expected) {
            return "VERSION_CONFLICT";
        }
    }

    private void verifyManualCloseAndLateConfirmationOrdering(
            JdbcDatabaseClient closingDatabase,
            String suffix) throws Exception {
        GatedJdbcDatabaseClient lateConfirmationDatabase = gatedDatabaseClient(5);
        JdbcPendingHandoffStore closingStore = new JdbcPendingHandoffStore(closingDatabase, 1);
        JdbcPendingHandoffStore lateConfirmationStore = new JdbcPendingHandoffStore(
                lateConfirmationDatabase, 1
        );
        String tenantId = "tenant-terminal-order-" + suffix;
        PendingHandoffRecord closeWinsSource = prepareManualReview(
                closingStore,
                tenantId,
                "real-close-wins-" + suffix,
                "real-close-wins-owner"
        );
        AfterSalesTicketCreateResult lateResult = result("AS-LATE-AFTER-CLOSE");
        lateConfirmationDatabase.pauseBeforeConfirmationFence(closeWinsSource.key());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> lateConfirmation = executor.submit(() -> {
                try {
                    lateConfirmationStore.confirm(
                            closeWinsSource.key(), lateResult, closeWinsSource.version()
                    );
                    return "UNEXPECTED_SUCCESS";
                } catch (PendingHandoffStateConflictException expected) {
                    return "STATE_CONFLICT";
                }
            });
            assertTrue(lateConfirmationDatabase.awaitConfirmationFence(3, TimeUnit.SECONDS),
                    "迟到 confirm 必须先停在终态围栏之前");

            PendingHandoffRecord closed = closingStore.close(manualContext(
                    closeWinsSource,
                    PendingHandoffEvidenceSource.NONE,
                    "CLOSE_WINS_RACE",
                    "关闭先获得行锁"
            )).record();
            assertEquals(PendingHandoffStatus.MANUALLY_CLOSED, closed.status());

            lateConfirmationDatabase.releaseConfirmationFence();
            assertEquals("STATE_CONFLICT", lateConfirmation.get(5, TimeUnit.SECONDS));
            PendingHandoffRecord stillClosed = closingStore.findByKey(
                    closeWinsSource.key()
            ).orElseThrow();
            assertEquals(PendingHandoffStatus.MANUALLY_CLOSED, stillClosed.status());
            assertEquals(0L, confirmationResultCount(closingDatabase, closeWinsSource.key()),
                    "close 先提交时，迟到 confirm 不能留下结果脏行");
        } finally {
            lateConfirmationDatabase.releaseConfirmationFence();
            executor.shutdownNow();
        }

        // 反向顺序：confirm 已经取得主表行锁时，并发 close 必须在同一把锁上等待。
        PendingHandoffRecord confirmWinsSource = prepareManualReview(
                closingStore,
                tenantId,
                "real-confirm-wins-" + suffix,
                "real-confirm-wins-owner"
        );
        GatedJdbcDatabaseClient competingCloseDatabase = gatedDatabaseClient(5);
        JdbcPendingHandoffStore competingCloseStore = new JdbcPendingHandoffStore(
                competingCloseDatabase, 1
        );
        lateConfirmationDatabase.pauseAfterConfirmationRowLock(confirmWinsSource.key());
        competingCloseDatabase.observeManualRowLockAttempt(
                tenantId, confirmWinsSource.outboxId()
        );
        ExecutorService orderingExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<String> confirmation = orderingExecutor.submit(() -> {
                PendingHandoffRecord confirmed = lateConfirmationStore.confirm(
                        confirmWinsSource.key(),
                        result("AS-CONFIRM-WINS"),
                        confirmWinsSource.version()
                );
                return confirmed.status().name();
            });
            assertTrue(lateConfirmationDatabase.awaitConfirmationFence(3, TimeUnit.SECONDS),
                    "confirm 必须在 SELECT FOR UPDATE 返回后持锁暂停");

            Future<String> close = orderingExecutor.submit(() -> {
                try {
                    competingCloseStore.close(manualContext(
                            confirmWinsSource,
                            PendingHandoffEvidenceSource.NONE,
                            "COMPETING_CLOSE",
                            "与 Worker 成功确认竞争"
                    ));
                    return "UNEXPECTED_SUCCESS";
                } catch (PendingHandoffStateConflictException expected) {
                    return "STATE_CONFLICT";
                } catch (PendingHandoffVersionConflictException expected) {
                    // confirm 的事务提交后还要用新连接投影主表；close 可能在投影前或后取得锁，
                    // 因而会分别看到“有效状态冲突”或“版本已前进”。两者都必须稳定拒绝覆盖。
                    return "VERSION_CONFLICT";
                }
            });
            assertTrue(competingCloseDatabase.awaitManualRowLockAttempt(3, TimeUnit.SECONDS),
                    "close 必须已经尝试获取同一主表行锁");
            assertThrows(
                    TimeoutException.class,
                    () -> close.get(300, TimeUnit.MILLISECONDS),
                    "confirm 持锁期间 close 不得完成"
            );

            lateConfirmationDatabase.releaseConfirmationFence();
            assertEquals(PendingHandoffStatus.CONFIRMED.name(),
                    confirmation.get(5, TimeUnit.SECONDS));
            assertTrue(Set.of("STATE_CONFLICT", "VERSION_CONFLICT")
                    .contains(close.get(5, TimeUnit.SECONDS)));
        } finally {
            lateConfirmationDatabase.releaseConfirmationFence();
            orderingExecutor.shutdownNow();
        }
        assertEquals(1L, confirmationResultCount(closingDatabase, confirmWinsSource.key()));
    }

    private long confirmationResultCount(JdbcDatabaseClient database,
                                         PendingHandoffKey key) {
        return database.queryLong("""
                SELECT COUNT(*)
                FROM cs_pending_handoff_result
                WHERE tenant_id = ? AND idempotency_key = ?
                """, key.tenantId(), key.idempotencyKey());
    }

    private void verifyAuditFailureRollsBackWholeDecision(JdbcDatabaseClient database,
                                                          String suffix) {
        AuditInsertFailingJdbcDatabaseClient failingDatabase = auditFailingDatabaseClient(5);
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(failingDatabase, 1);
        String tenantId = "tenant-audit-rollback-" + suffix;
        PendingHandoffRecord manual = prepareManualReview(
                store,
                tenantId,
                "real-audit-rollback-" + suffix,
                "real-audit-rollback-owner"
        );

        /*
         * SQL 替身只能模拟 rollback。故障 Client 仍让 result INSERT 和主表 UPDATE 在真实 MySQL 的同一
         * Connection 上执行，只在 audit INSERT 交给驱动前抛错；随后由生产 inTransaction 执行真实
         * rollback。这样不要求应用账号拥有 CREATE TRIGGER/SUPER 等不合理的生产权限。
         */
        failingDatabase.failNextAuditInsert();
        assertThrows(
                IllegalStateException.class,
                () -> store.confirm(
                        manualContext(
                                manual,
                                PendingHandoffEvidenceSource.ADMIN_PROVIDED,
                                "FORCED_AUDIT_FAILURE",
                                "触发真实数据库回滚"
                        ),
                        result("AS-MUST-ROLLBACK-REAL")
                )
        );

        PendingHandoffRecord unchanged = store.findByTenantAndOutboxId(
                tenantId, manual.outboxId()
        ).orElseThrow();
        assertEquals(PendingHandoffStatus.NEEDS_MANUAL_REVIEW, unchanged.status());
        assertEquals(manual.version(), unchanged.version());
        assertEquals(0L, confirmationResultCount(database, manual.key()));
        assertTrue(store.findAuditPage(tenantId, manual.outboxId(), null, 10).events().isEmpty());
    }

    private void verifyAuditKeysetTieBreak(JdbcDatabaseClient database,
                                           String suffix) {
        JdbcPendingHandoffStore store = new JdbcPendingHandoffStore(database, 1);
        String tenantId = "tenant-audit-page-" + suffix;
        PendingHandoffRecord firstManual = prepareManualReview(
                store,
                tenantId,
                "real-audit-page-" + suffix,
                "real-audit-page-owner-1"
        );
        PendingHandoffRecord retried = store.retry(manualContext(
                firstManual,
                PendingHandoffEvidenceSource.NONE,
                "FIRST_RETRY",
                "第一次人工决定"
        )).record();
        PendingHandoffClaim secondClaim = store.tryClaim(
                retried.key(),
                retried.version(),
                "real-audit-page-owner-2",
                retried.nextAttemptAt(),
                5_000
        ).orElseThrow();
        PendingHandoffRecord secondManual = store.markNeedsManualReview(
                secondClaim, "REAL_MYSQL_REVIEW_AGAIN"
        );
        store.close(manualContext(
                secondManual,
                PendingHandoffEvidenceSource.NONE,
                "FINAL_CLOSE",
                "第二次人工决定"
        ));

        // 把两条真实生成的事件固定到同一毫秒，只改变测试排序坐标，不修改业务字段。
        long sameOccurredAt = 12_345L;
        database.update("""
                UPDATE cs_pending_handoff_review_audit
                SET occurred_at = ?
                WHERE tenant_id = ? AND outbox_id = ?
                """, sameOccurredAt, tenantId, firstManual.outboxId());

        PendingHandoffReviewAuditPage firstPage = store.findAuditPage(
                tenantId, firstManual.outboxId(), null, 1
        );
        PendingHandoffReviewAuditPage secondPage = store.findAuditPage(
                tenantId, firstManual.outboxId(), firstPage.nextCursor(), 1
        );

        assertTrue(firstPage.hasMore());
        assertEquals(1, firstPage.events().size());
        assertEquals(1, secondPage.events().size());
        assertTrue(!secondPage.hasMore());
        assertTrue(firstPage.events().get(0).auditId()
                        .compareTo(secondPage.events().get(0).auditId()) > 0,
                "相同 occurredAt 时必须按 auditId DESC 稳定排序");
        assertTrue(!firstPage.events().get(0).auditId()
                        .equals(secondPage.events().get(0).auditId()),
                "复合游标翻页不能重复返回同一审计事件");
    }

    /**
     * 真实数据库用例统一从当前记录构造可信人工上下文，避免每个调用点手工排列八个字段。
     * actorId 在真实应用中由已验证 JWT Principal 提供；IT 使用固定管理员标识验证它会进入审计表。
     */
    private PendingHandoffManualDecisionContext manualContext(
            PendingHandoffRecord record,
            PendingHandoffEvidenceSource evidenceSource,
            String reasonCode,
            String decisionNote) {
        return new PendingHandoffManualDecisionContext(
                record.tenantId(),
                record.outboxId(),
                record.version(),
                "admin-real-mysql-it",
                PendingHandoffReviewAuthority.ADMIN,
                reasonCode,
                decisionNote,
                evidenceSource
        );
    }

    private AfterSalesTicketCreateResult result(String ticketId) {
        return new AfterSalesTicketCreateResult(
                ticketId, "PENDING", "2026-07-13T00:00:02Z"
        );
    }

    private void verifyQueryTimeout(JdbcDatabaseClient database) {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(
                IllegalStateException.class,
                () -> database.queryLong("SELECT SLEEP(2)")
        ));
    }

    private JdbcDatabaseClient databaseClient(int queryTimeoutSeconds) {
        String url = System.getProperty(
                "smartcs.real.mysql.url",
                "jdbc:mysql://127.0.0.1:3307/smart_cs_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
        );
        String username = System.getProperty("smartcs.real.mysql.username", "smartcs");
        String password = System.getProperty("smartcs.real.mysql.password", "smartcs-local-password");
        return new JdbcDatabaseClient(
                "jdbc",
                "com.mysql.cj.jdbc.Driver",
                url,
                username,
                password,
                3_000,
                10_000,
                queryTimeoutSeconds
        );
    }

    private GatedJdbcDatabaseClient gatedDatabaseClient(int queryTimeoutSeconds) {
        String url = System.getProperty(
                "smartcs.real.mysql.url",
                "jdbc:mysql://127.0.0.1:3307/smart_cs_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
        );
        String username = System.getProperty("smartcs.real.mysql.username", "smartcs");
        String password = System.getProperty("smartcs.real.mysql.password", "smartcs-local-password");
        return new GatedJdbcDatabaseClient(url, username, password, queryTimeoutSeconds);
    }

    private AuditInsertFailingJdbcDatabaseClient auditFailingDatabaseClient(int queryTimeoutSeconds) {
        String url = System.getProperty(
                "smartcs.real.mysql.url",
                "jdbc:mysql://127.0.0.1:3307/smart_cs_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
        );
        String username = System.getProperty("smartcs.real.mysql.username", "smartcs");
        String password = System.getProperty("smartcs.real.mysql.password", "smartcs-local-password");
        return new AuditInsertFailingJdbcDatabaseClient(
                url, username, password, queryTimeoutSeconds
        );
    }

    /**
     * 只在真实 MySQL IT 中使用的故障注入器。它不模拟数据库，所有正常 SQL 仍委托给同一个事务连接；
     * 唯一作用是在指定的一次审计 INSERT 前抛异常，以验证生产 rollback 路径。
     */
    private static final class AuditInsertFailingJdbcDatabaseClient extends JdbcDatabaseClient {

        private final AtomicBoolean failNextAuditInsert = new AtomicBoolean(false);

        private AuditInsertFailingJdbcDatabaseClient(String url,
                                                     String username,
                                                     String password,
                                                     int queryTimeoutSeconds) {
            super(
                    "jdbc",
                    "com.mysql.cj.jdbc.Driver",
                    url,
                    username,
                    password,
                    3_000,
                    10_000,
                    queryTimeoutSeconds
            );
        }

        private void failNextAuditInsert() {
            failNextAuditInsert.set(true);
        }

        @Override
        public <T> T inTransaction(TransactionWork<T> work) {
            return super.inTransaction(operations -> work.execute(new JdbcDatabaseOperations() {
                @Override
                public int update(String sql, Object... args) {
                    if (sql.trim().startsWith("INSERT INTO cs_pending_handoff_review_audit")
                            && failNextAuditInsert.compareAndSet(true, false)) {
                        throw new IllegalStateException("Forced audit insert failure for real MySQL IT");
                    }
                    return operations.update(sql, args);
                }

                @Override
                public <R> Optional<R> queryOne(String sql,
                                                RowMapper<R> mapper,
                                                Object... args) {
                    return operations.queryOne(sql, mapper, args);
                }

                @Override
                public <R> List<R> queryList(String sql,
                                            RowMapper<R> mapper,
                                            Object... args) {
                    return operations.queryList(sql, mapper, args);
                }
            }));
        }
    }

    /** 在普通 confirm 行锁前后建立可控顺序，并观测人工 close 已尝试获取同一行锁。 */
    private static final class GatedJdbcDatabaseClient extends JdbcDatabaseClient {

        private final AtomicBoolean confirmationFenceArmed = new AtomicBoolean(false);
        private final AtomicBoolean manualRowLockObservationArmed = new AtomicBoolean(false);
        private volatile PendingHandoffKey fencedKey;
        private volatile ConfirmationFencePosition confirmationFencePosition =
                ConfirmationFencePosition.BEFORE_CONFIRMATION_STEP;
        private volatile CountDownLatch confirmationFenceReached = new CountDownLatch(0);
        private volatile CountDownLatch confirmationFenceRelease = new CountDownLatch(0);
        private volatile String observedManualTenantId;
        private volatile String observedManualOutboxId;
        private volatile CountDownLatch manualRowLockAttempted = new CountDownLatch(0);

        private GatedJdbcDatabaseClient(String url,
                                        String username,
                                        String password,
                                        int queryTimeoutSeconds) {
            super(
                    "jdbc",
                    "com.mysql.cj.jdbc.Driver",
                    url,
                    username,
                    password,
                    3_000,
                    10_000,
                    queryTimeoutSeconds
            );
        }

        private void pauseBeforeConfirmationFence(PendingHandoffKey key) {
            armConfirmationFence(key, ConfirmationFencePosition.BEFORE_CONFIRMATION_STEP);
        }

        private void pauseAfterConfirmationRowLock(PendingHandoffKey key) {
            armConfirmationFence(key, ConfirmationFencePosition.AFTER_ROW_LOCK);
        }

        private void armConfirmationFence(PendingHandoffKey key,
                                          ConfirmationFencePosition position) {
            fencedKey = key;
            confirmationFencePosition = position;
            confirmationFenceReached = new CountDownLatch(1);
            confirmationFenceRelease = new CountDownLatch(1);
            confirmationFenceArmed.set(true);
        }

        private void observeManualRowLockAttempt(String tenantId, String outboxId) {
            observedManualTenantId = tenantId;
            observedManualOutboxId = outboxId;
            manualRowLockAttempted = new CountDownLatch(1);
            manualRowLockObservationArmed.set(true);
        }

        private boolean awaitConfirmationFence(long timeout, TimeUnit unit)
                throws InterruptedException {
            return confirmationFenceReached.await(timeout, unit);
        }

        private void releaseConfirmationFence() {
            confirmationFenceRelease.countDown();
        }

        private boolean awaitManualRowLockAttempt(long timeout, TimeUnit unit)
                throws InterruptedException {
            return manualRowLockAttempted.await(timeout, unit);
        }

        @Override
        public int update(String sql, Object... args) {
            pauseBeforeTargetConfirmationStep(sql, args);
            return super.update(sql, args);
        }

        @Override
        public <T> T inTransaction(TransactionWork<T> work) {
            return super.inTransaction(operations -> work.execute(new JdbcDatabaseOperations() {
                @Override
                public int update(String sql, Object... args) {
                    pauseBeforeTargetConfirmationStep(sql, args);
                    return operations.update(sql, args);
                }

                @Override
                public <R> Optional<R> queryOne(String sql,
                                                RowMapper<R> mapper,
                                                Object... args) {
                    observeTargetManualRowLock(sql, args);
                    pauseBeforeTargetConfirmationStep(sql, args);
                    Optional<R> result = operations.queryOne(sql, mapper, args);
                    pauseAfterTargetConfirmationRowLock(sql, args);
                    return result;
                }

                @Override
                public <R> List<R> queryList(String sql,
                                            RowMapper<R> mapper,
                                            Object... args) {
                    return operations.queryList(sql, mapper, args);
                }
            }));
        }

        private void pauseBeforeTargetConfirmationStep(String sql, Object[] args) {
            if (confirmationFencePosition != ConfirmationFencePosition.BEFORE_CONFIRMATION_STEP) {
                return;
            }
            pauseIfTargetConfirmationStep(sql, args, false);
        }

        private void pauseAfterTargetConfirmationRowLock(String sql, Object[] args) {
            if (confirmationFencePosition != ConfirmationFencePosition.AFTER_ROW_LOCK) {
                return;
            }
            pauseIfTargetConfirmationStep(sql, args, true);
        }

        private void pauseIfTargetConfirmationStep(String sql,
                                                   Object[] args,
                                                   boolean rowLockOnly) {
            if (!confirmationFenceArmed.get() || args == null || args.length < 2) {
                return;
            }
            String normalized = sql.trim();
            boolean terminalRowLock = normalized.contains("FROM cs_pending_handoff")
                    && normalized.contains("idempotency_key = ?")
                    && normalized.contains("FOR UPDATE");
            boolean resultInsert = normalized.startsWith(
                    "INSERT INTO cs_pending_handoff_result"
            );
            PendingHandoffKey candidate = new PendingHandoffKey(
                    args[0].toString(), args[1].toString()
            );
            if ((!terminalRowLock && (rowLockOnly || !resultInsert))
                    || !candidate.equals(fencedKey)
                    || !confirmationFenceArmed.compareAndSet(true, false)) {
                return;
            }
            confirmationFenceReached.countDown();
            await(confirmationFenceRelease);
        }

        private void observeTargetManualRowLock(String sql, Object[] args) {
            if (!manualRowLockObservationArmed.get() || args == null || args.length < 2) {
                return;
            }
            String normalized = sql.trim();
            boolean manualRowLock = normalized.contains("FROM cs_pending_handoff")
                    && normalized.contains("outbox_id = ?")
                    && normalized.contains("FOR UPDATE");
            if (!manualRowLock
                    || !observedManualTenantId.equals(args[0].toString())
                    || !observedManualOutboxId.equals(args[1].toString())
                    || !manualRowLockObservationArmed.compareAndSet(true, false)) {
                return;
            }
            manualRowLockAttempted.countDown();
        }

        private enum ConfirmationFencePosition {
            BEFORE_CONFIRMATION_STEP,
            AFTER_ROW_LOCK
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", e);
        }
    }
}
