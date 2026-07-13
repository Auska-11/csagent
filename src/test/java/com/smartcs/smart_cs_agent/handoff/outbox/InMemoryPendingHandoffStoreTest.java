package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryPendingHandoffStoreTest {

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
                "idem-unknown",
                "SocketTimeoutException",
                prepared.version()
        );

        assertEquals(PendingHandoffStatus.RESULT_UNKNOWN, unknown.status());
        assertEquals("SocketTimeoutException", unknown.failureType());
        assertEquals(1, unknown.attemptCount());
        assertEquals(2, unknown.version());
        assertThrows(
                PendingHandoffVersionConflictException.class,
                () -> store.markResultUnknown("idem-unknown", "Again", prepared.version())
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

        PendingHandoffRecord confirmed = store.confirm("idem-success", result, prepared.version());
        PendingHandoffRecord lateTimeout = store.markResultUnknown(
                "idem-success", "SocketTimeoutException", confirmed.version()
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

    private PendingHandoffCommand command(String idempotencyKey, String summary) {
        return new PendingHandoffCommand(
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
