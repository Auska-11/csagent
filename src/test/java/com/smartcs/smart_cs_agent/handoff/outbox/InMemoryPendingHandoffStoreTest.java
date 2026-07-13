package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryPendingHandoffStoreTest {

    @Test
    void shouldSaveResultUnknownRecordWithOriginalRequest() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffCommand command = command("idem-001", "第一次摘要");

        PendingHandoffRecord saved = store.recordUnknown(command);

        assertEquals(PendingHandoffStatus.RESULT_UNKNOWN, saved.status());
        assertEquals("idem-001", saved.request().idempotencyKey());
        assertEquals("第一次摘要", saved.summary());
        assertEquals(1, saved.version());
        assertEquals(1, store.size());
    }

    @Test
    void shouldReturnSameRecordForSameIdempotencyKeyAndPayload() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        PendingHandoffCommand command = command("idem-replay", "相同摘要");

        PendingHandoffRecord first = store.recordUnknown(command);
        PendingHandoffRecord second = store.recordUnknown(command);

        assertSame(first, second);
        assertEquals(1, store.size());
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayload() {
        InMemoryPendingHandoffStore store = new InMemoryPendingHandoffStore();
        store.recordUnknown(command("idem-conflict", "原始摘要"));

        assertThrows(
                PendingHandoffConflictException.class,
                () -> store.recordUnknown(command("idem-conflict", "被修改的摘要"))
        );
        assertEquals(1, store.size());
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
                ),
                "IllegalStateException"
        );
    }
}
