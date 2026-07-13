package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewNotFoundException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryPendingHandoffStoreTest {

    private static final String TENANT = "tenant-a";

    @Test
    void shouldPrepareRequestBeforeRemoteCall() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffCommand command = command("idem-001", "第一次摘要");

        PendingHandoffPreparation preparation = store.prepare(command);
        PendingHandoffRecord saved = preparation.record();

        assertEquals(true, preparation.created());
        assertEquals(PendingHandoffStatus.PREPARED, saved.status());
        assertEquals("idem-001", saved.request().idempotencyKey());
        assertEquals("第一次摘要", saved.summary());
        assertEquals(1, saved.version());
        assertEquals(1, store.size());
    }

    @Test
    void shouldReturnSameRecordForSameIdempotencyKeyAndPayload() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffCommand command = command("idem-replay", "相同摘要");

        PendingHandoffPreparation first = store.prepare(command);
        PendingHandoffPreparation second = store.prepare(command);

        assertEquals(true, first.created());
        assertEquals(false, second.created());
        assertSame(first.record(), second.record());
        assertEquals(1, store.size());
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayload() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        store.prepare(command("idem-conflict", "原始摘要"));

        assertThrows(
                PendingHandoffConflictException.class,
                () -> store.prepare(command("idem-conflict", "被修改的摘要"))
        );
        assertEquals(1, store.size());
    }

    @Test
    void shouldAdvanceUnknownStateWithVersionCas() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffRecord prepared = store.prepare(command("idem-unknown", "待核对")).record();

        PendingHandoffRecord unknown = store.markResultUnknown(
                prepared.key(),
                "SocketTimeoutException",
                prepared.version()
        );

        assertEquals(PendingHandoffStatus.RESULT_UNKNOWN, unknown.status());
        assertEquals("SocketTimeoutException", unknown.failureType());
        assertEquals(1, unknown.attemptCount());
        assertEquals(2, unknown.version());
        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.markResultUnknown(prepared.key(), "Again", prepared.version())
        );
    }

    @Test
    void shouldPrioritizeStaleVersionOverIllegalStateForMarkResultUnknown() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore(1);
        PendingHandoffRecord manual = moveToManualReview(
                store,
                command("tenant-a", "unknown-conflict-priority", "普通状态机冲突优先级"),
                "owner-unknown-priority"
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
    void shouldPersistAndReplayConfirmedResult() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffCommand command = command("idem-success", "已成功");
        PendingHandoffRecord prepared = store.prepare(command).record();
        AfterSalesTicketCreateResult result = new AfterSalesTicketCreateResult(
                "AS-100", "PENDING", "2026-07-13T00:00:00Z"
        );

        PendingHandoffRecord confirmed = store.confirm(prepared.key(), result, prepared.version());
        PendingHandoffRecord lateTimeout = store.markResultUnknown(
                prepared.key(), "SocketTimeoutException", confirmed.version()
        );
        PendingHandoffPreparation replay = store.prepare(command);

        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals(result, confirmed.confirmedResult());
        assertEquals(2, confirmed.version());
        assertEquals(PendingHandoffStatus.CONFIRMED, lateTimeout.status());
        assertEquals(result, lateTimeout.confirmedResult());
        assertEquals(false, replay.created());
        assertEquals(result, replay.record().confirmedResult());
    }

    @Test
    void shouldAllowOnlyOneLeaseOwnerAndTakeOverAfterExpiry() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffRecord prepared = store.prepare(command("idem-lease", "租约测试")).record();
        long now = prepared.nextAttemptAt();

        assertTrue(store.findDue(now - 1, 10).isEmpty(), "PREPARED 宽限期内后台不能抢占同步调用");
        assertEquals(1, store.findDue(now, 10).size());
        PendingHandoffClaim first = store.tryClaim(
                prepared.key(), prepared.version(), "owner-1", now, 1_000
        ).orElseThrow();
        assertEquals(PendingHandoffStatus.PROCESSING, first.record().status());
        assertTrue(store.tryClaim(
                prepared.key(), first.record().version(), "owner-2", now + 500, 1_000
        ).isEmpty());

        PendingHandoffClaim takeover = store.tryClaim(
                prepared.key(), first.record().version(), "owner-2", now + 1_000, 1_000
        ).orElseThrow();
        assertEquals("owner-2", takeover.ownerToken());
        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.scheduleRetry(first, "LateOwner", now + 2_000)
        );

        PendingHandoffRecord retry = store.scheduleRetry(
                takeover, "SocketTimeoutException", now + 3_000
        );
        assertEquals(PendingHandoffStatus.RETRY_SCHEDULED, retry.status());
        assertEquals(1, retry.attemptCount());
        assertTrue(store.findDue(now + 2_999, 10).isEmpty());
        assertEquals(1, store.findDue(now + 3_000, 10).size());
    }

    @Test
    void shouldRejectOwnerAfterLeaseHasExpired() throws InterruptedException {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore(1);
        PendingHandoffRecord prepared = store.prepare(command("idem-expired", "过期租约")).record();
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
    void shouldIsolateSameIdempotencyKeyBetweenTenants() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffCommand tenantA = command("tenant-a", "same-key", "A 租户摘要");
        PendingHandoffCommand tenantB = command("tenant-b", "same-key", "B 租户摘要");

        PendingHandoffRecord savedA = store.prepare(tenantA).record();
        PendingHandoffRecord savedB = store.prepare(tenantB).record();

        assertEquals(2, store.size());
        assertEquals("tenant-a", savedA.tenantId());
        assertEquals("tenant-b", savedB.tenantId());
        assertEquals("A 租户摘要", store.findByKey(tenantA.key()).orElseThrow().summary());
        assertEquals("B 租户摘要", store.findByKey(tenantB.key()).orElseThrow().summary());

        // tenant A 的 key 不能读取或更新 tenant B 的记录；复合键让两条记录完全独立。
        AfterSalesTicketCreateResult result = new AfterSalesTicketCreateResult(
                "AS-TENANT-A", "PENDING", "2026-07-13T00:00:00Z"
        );
        store.confirm(tenantA.key(), result, savedA.version());
        assertEquals(PendingHandoffStatus.PREPARED, store.findByKey(tenantB.key()).orElseThrow().status());

        AfterSalesTicketCreateResult tenantBResult = new AfterSalesTicketCreateResult(
                "AS-TENANT-B", "PENDING", "2026-07-13T00:00:01Z"
        );
        store.confirm(tenantB.key(), tenantBResult, savedB.version());
        assertEquals("AS-TENANT-A", store.findByKey(tenantA.key()).orElseThrow().confirmedResult().ticketId());
        assertEquals("AS-TENANT-B", store.findByKey(tenantB.key()).orElseThrow().confirmedResult().ticketId());
    }

    @Test
    void shouldKeepClaimsIndependentAndHideUnknownTenant() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffRecord tenantA = store.prepare(command("tenant-a", "claim-key", "A")).record();
        PendingHandoffRecord tenantB = store.prepare(command("tenant-b", "claim-key", "B")).record();
        long now = Math.max(tenantA.nextAttemptAt(), tenantB.nextAttemptAt());

        PendingHandoffClaim claimA = store.tryClaim(
                tenantA.key(), tenantA.version(), "owner-a", now, 1_000
        ).orElseThrow();
        PendingHandoffClaim claimB = store.tryClaim(
                tenantB.key(), tenantB.version(), "owner-b", now, 1_000
        ).orElseThrow();

        assertEquals("owner-a", claimA.ownerToken());
        assertEquals("owner-b", claimB.ownerToken());
        assertTrue(store.tryClaim(
                new PendingHandoffKey("tenant-c", "claim-key"), 1, "owner-c", now, 1_000
        ).isEmpty());
    }

    @Test
    void shouldRejectIdempotencyKeyWithSurroundingWhitespace() {
        assertThrows(
                IllegalArgumentException.class,
                () -> command(TENANT, " idem-with-space ", "摘要")
        );
    }

    @Test
    void shouldPageManualReviewsByTenantWithoutDuplicates() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore(1);
        PendingHandoffRecord first = moveToManualReview(store, command("tenant-a", "page-1", "一"), "owner-1");
        PendingHandoffRecord second = moveToManualReview(store, command("tenant-a", "page-2", "二"), "owner-2");
        PendingHandoffRecord third = moveToManualReview(store, command("tenant-a", "page-3", "三"), "owner-3");
        moveToManualReview(store, command("tenant-b", "page-1", "另一个租户"), "owner-b");

        PendingHandoffReviewPage pageOne = store.findReviewPage(
                "tenant-a", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, null, 2
        );
        PendingHandoffReviewPage pageTwo = store.findReviewPage(
                "tenant-a",
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                pageOne.nextCursor(),
                2
        );

        assertTrue(pageOne.hasMore());
        assertEquals(2, pageOne.records().size());
        assertEquals(false, pageTwo.hasMore());
        assertEquals(1, pageTwo.records().size());
        List<String> combinedIds = java.util.stream.Stream.concat(
                        pageOne.records().stream(),
                        pageTwo.records().stream()
                )
                .map(PendingHandoffRecord::outboxId)
                .toList();
        assertEquals(3, combinedIds.stream().distinct().count());
        assertTrue(combinedIds.containsAll(List.of(first.outboxId(), second.outboxId(), third.outboxId())));
        assertTrue(pageOne.records().stream().allMatch(record -> record.tenantId().equals("tenant-a")));
        assertTrue(store.findByTenantAndOutboxId("tenant-b", first.outboxId()).isEmpty());
    }

    @Test
    void shouldRejectInvalidReviewPaginationArguments() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();

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
    void shouldPageConfirmedRecordsByEffectiveStatus() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore(1);
        PendingHandoffRecord prepared = store.prepare(
                command("tenant-a", "confirmed-page", "已确认")
        ).record();
        AfterSalesTicketCreateResult result = result("AS-CONFIRMED-PAGE");
        PendingHandoffRecord confirmed = store.confirm(prepared.key(), result, prepared.version());
        moveToManualReview(
                store,
                command("tenant-a", "manual-page-control", "仍待人工复核"),
                "owner-control"
        );
        PendingHandoffRecord otherTenant = store.prepare(
                command("tenant-b", "confirmed-page", "另一个租户已确认")
        ).record();
        store.confirm(otherTenant.key(), result("AS-OTHER-TENANT"), otherTenant.version());

        PendingHandoffReviewPage page = store.findReviewPage(
                "tenant-a", PendingHandoffStatus.CONFIRMED, null, 10
        );

        assertFalse(page.hasMore());
        assertEquals(1, page.records().size());
        assertEquals(confirmed.outboxId(), page.records().get(0).outboxId());
        assertEquals(PendingHandoffStatus.CONFIRMED, page.records().get(0).status());
        assertEquals(result, page.records().get(0).confirmedResult());
    }

    @Test
    void shouldApplyManualRetryConfirmAndCloseWithCas() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore(1);
        PendingHandoffRecord retrySource = moveToManualReview(
                store, command("tenant-a", "manual-retry", "重试"), "owner-retry"
        );
        PendingHandoffRecord confirmSource = moveToManualReview(
                store, command("tenant-a", "manual-confirm", "确认"), "owner-confirm"
        );
        PendingHandoffRecord closeSource = moveToManualReview(
                store, command("tenant-a", "manual-close", "关闭"), "owner-close"
        );

        long scheduledAt = 2_000L;
        PendingHandoffRecord retried = store.retryFromManualReview(
                "tenant-a", retrySource.outboxId(), retrySource.version(), scheduledAt
        );
        AfterSalesTicketCreateResult result = new AfterSalesTicketCreateResult(
                "AS-MANUAL", "PENDING", "2026-07-13T00:00:00Z"
        );
        PendingHandoffRecord confirmed = store.confirmFromManualReview(
                "tenant-a", confirmSource.outboxId(), result, confirmSource.version()
        );
        PendingHandoffRecord closed = store.closeFromManualReview(
                "tenant-a", closeSource.outboxId(), closeSource.version(), 3_000L
        );

        assertEquals(PendingHandoffStatus.RETRY_SCHEDULED, retried.status());
        assertEquals(scheduledAt, retried.nextAttemptAt());
        assertEquals(retrySource.attemptCount(), retried.attemptCount(),
                "人工排队本身不等于发生了一次远端调用");
        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals(result, confirmed.confirmedResult());
        assertEquals(confirmSource.attemptCount(), confirmed.attemptCount());
        assertEquals(PendingHandoffStatus.MANUALLY_CLOSED, closed.status());
        assertEquals(Long.MAX_VALUE, closed.nextAttemptAt());

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
                () -> store.confirm(
                        closed.key(),
                        new AfterSalesTicketCreateResult(
                                "AS-TOO-LATE", "PENDING", "2026-07-13T00:00:02Z"
                        ),
                        closed.version()
                )
        );
        assertThrows(
                PendingHandoffStateConflictException.class,
                () -> store.markResultUnknown(closed.key(), "TooLate", closed.version())
        );
    }

    @Test
    void shouldApplyTheSameConflictRulesToAllManualActions() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore(1);
        PendingHandoffRecord manual = moveToManualReview(
                store,
                command("tenant-a", "manual-conflict-matrix", "冲突矩阵"),
                "owner-matrix"
        );

        // 三种动作都必须遵守同一个版本 CAS；不能只验证 close 而漏掉 retry/confirm。
        assertAllManualActionsThrow(
                PendingHandoffVersionConflictException.class,
                store,
                "tenant-a",
                manual,
                manual.version() - 1
        );
        // “不存在”和“属于其他租户”必须表现一致，避免通过异常差异探测其他租户数据。
        assertAllManualActionsThrow(
                PendingHandoffReviewNotFoundException.class,
                store,
                "tenant-b",
                manual,
                manual.version()
        );
        assertEquals(
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                store.findByTenantAndOutboxId("tenant-a", manual.outboxId()).orElseThrow().status()
        );
        assertNull(store.findByKey(manual.key()).orElseThrow().confirmedResult());

        PendingHandoffRecord prepared = store.prepare(
                command("tenant-a", "invalid-manual-state", "尚未进入人工复核")
        ).record();
        // 即使当前状态也不允许人工动作，stale version 仍必须优先报 CAS 冲突。
        assertAllManualActionsThrow(
                PendingHandoffVersionConflictException.class,
                store,
                "tenant-a",
                prepared,
                prepared.version() - 1
        );
        assertAllManualActionsThrow(
                PendingHandoffStateConflictException.class,
                store,
                "tenant-a",
                prepared,
                prepared.version()
        );
        assertEquals(PendingHandoffStatus.PREPARED, store.findByKey(prepared.key()).orElseThrow().status());
    }

    private void assertAllManualActionsThrow(
            Class<? extends Throwable> expectedType,
            InMemoryPendingHandoffStore store,
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
                        tenantId, source.outboxId(), result("AS-CONFLICT-MATRIX"), expectedVersion
                )
        );
        assertThrows(
                expectedType,
                () -> store.closeFromManualReview(
                        tenantId, source.outboxId(), expectedVersion, 5_000L
                )
        );
    }

    private PendingHandoffRecord moveToManualReview(InMemoryPendingHandoffStore store,
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
                "conv-outbox",
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
        return new AfterSalesTicketCreateResult(
                ticketId, "PENDING", "2026-07-13T00:00:00Z"
        );
    }
}
