package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
