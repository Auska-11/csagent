package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.handoff.outbox.InMemoryPendingHandoffStore;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffPreparation;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HandoffToolsTest {

    @Test
    void shouldCreateMemoryTicketByDefault() {
        HandoffTools tools = new HandoffTools();

        HandoffTicketData ticket = tools.createHandoffTicket(
                "USER-001",
                "conv-memory",
                "USER_REQUESTED_HUMAN",
                "用户要求人工客服继续处理"
        );

        assertTrue(ticket.getTicketId().startsWith("HD-"));
        assertEquals("USER-001", ticket.getUserId());
        assertEquals("conv-memory", ticket.getConversationId());
        assertEquals("WAITING_HUMAN_AGENT", ticket.getStatus());
    }

    @Test
    void shouldMapHandoffToAfterSalesTicketRequest() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("after-sales-http");
        FakeAfterSalesTicketClient client = new FakeAfterSalesTicketClient(
                new AfterSalesTicketCreateResult("AS-123", "PENDING", "2026-07-09T00:00:00Z")
        );
        InMemoryPendingHandoffStore pendingStore = new InMemoryPendingHandoffStore();
        HandoffTools tools = new HandoffTools(properties, client, pendingStore);

        HandoffTicketData ticket = tools.createHandoffTicket(
                "USER-001",
                "conv-001",
                "COMPLAINT",
                "用户投诉蓝牙耳机坏了，希望尽快赔偿"
        );

        assertEquals("AS-123", ticket.getTicketId());
        assertEquals("PENDING", ticket.getStatus());

        AfterSalesTicketCreateRequest request = client.lastRequest;
        assertNotNull(request);
        assertEquals("USER-001", request.customerId());
        assertEquals("bluetooth", request.productLine());
        assertEquals("complaint", request.issueType());
        assertEquals("URGENT", request.priority());
        assertTrue(request.idempotencyKey().startsWith("agent-handoff-"));
        assertFalse(request.idempotencyKey().contains("conv-001"));
        assertFalse(request.idempotencyKey().contains("USER-001"));
        assertTrue(request.title().startsWith("Agent 转人工："));
        assertTrue(request.description().contains("会话ID：conv-001"));

        PendingHandoffRecord confirmed = pendingStore
                .findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow();
        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals("AS-123", confirmed.confirmedResult().ticketId());

        // 模拟 Capability 幂等结果尚未落库后的重试：Outbox 会直接回放，不再调用远端。
        HandoffTicketData replay = tools.createHandoffTicket(
                "USER-001",
                "conv-001",
                "COMPLAINT",
                "用户投诉蓝牙耳机坏了，希望尽快赔偿"
        );
        assertEquals("AS-123", replay.getTicketId());
        assertEquals(1, client.callCount);
    }

    @Test
    void shouldNotFabricatePendingTicketWhenAfterSalesPlatformFails() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("after-sales-http");
        FakeAfterSalesTicketClient client = new FakeAfterSalesTicketClient(null);
        client.fail = true;
        InMemoryPendingHandoffStore pendingStore = new InMemoryPendingHandoffStore();
        HandoffTools tools = new HandoffTools(properties, client, pendingStore);

        HandoffTicketCreationException error = assertThrows(
                HandoffTicketCreationException.class,
                () -> tools.createHandoffTicket(
                        "USER-001",
                        "conv-fallback",
                        "COMPLAINT",
                        "用户投诉，需要人工处理"
                )
        );

        assertEquals("Remote handoff result could not be confirmed", error.getMessage());
        assertFalse(error.getMessage().contains("remote platform unavailable"));

        PendingHandoffRecord pending = pendingStore
                .findByIdempotencyKey(client.lastRequest.idempotencyKey())
                .orElseThrow();
        assertEquals("conv-fallback", pending.conversationId());
        assertEquals("IllegalStateException", pending.failureType());
        assertEquals(PendingHandoffStatus.RESULT_UNKNOWN, pending.status());
        assertEquals(1, pendingStore.size());

        HandoffTicketCreationException replayError = assertThrows(
                HandoffTicketCreationException.class,
                () -> tools.createHandoffTicket(
                        "USER-001",
                        "conv-fallback",
                        "COMPLAINT",
                        "用户投诉，需要人工处理"
                )
        );
        assertEquals("Remote handoff is awaiting reconciliation", replayError.getMessage());
        assertEquals(1, client.callCount, "结果未知记录存在时不能同步盲目重试远端副作用");
    }

    @Test
    void shouldFailClosedBeforeRemoteCallWhenOutboxPreparationFails() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("after-sales-http");
        FakeAfterSalesTicketClient client = new FakeAfterSalesTicketClient(null);
        client.fail = true;
        PendingHandoffStore unavailableStore = new PendingHandoffStore() {
            @Override
            public PendingHandoffPreparation prepare(PendingHandoffCommand command) {
                throw new IllegalStateException("jdbc:mysql://internal password=do-not-leak");
            }

            @Override
            public PendingHandoffRecord markResultUnknown(String idempotencyKey,
                                                          String failureType,
                                                          long expectedVersion) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PendingHandoffRecord confirm(String idempotencyKey,
                                                AfterSalesTicketCreateResult result,
                                                long expectedVersion) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<PendingHandoffRecord> findByIdempotencyKey(String idempotencyKey) {
                return Optional.empty();
            }
        };
        HandoffTools tools = new HandoffTools(properties, client, unavailableStore);

        HandoffTicketCreationException error = assertThrows(
                HandoffTicketCreationException.class,
                () -> tools.createHandoffTicket(
                        "USER-001",
                        "conv-double-failure",
                        "COMPLAINT",
                        "用户投诉，需要人工处理"
                )
        );

        assertEquals(
                "Pending handoff request could not be prepared",
                error.getMessage()
        );
        assertFalse(error.getMessage().contains("internal"));
        assertFalse(error.getMessage().contains("do-not-leak"));
        assertEquals(0, client.callCount, "Outbox 准备失败时不能绕过它调用远端");
    }

    @Test
    void shouldUseDifferentOpaqueKeysForTwoOperationsInSameConversation() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("after-sales-http");
        FakeAfterSalesTicketClient client = new FakeAfterSalesTicketClient(
                new AfterSalesTicketCreateResult("AS-200", "PENDING", "2026-07-13T00:00:00Z")
        );
        InMemoryPendingHandoffStore pendingStore = new InMemoryPendingHandoffStore();
        HandoffTools tools = new HandoffTools(properties, client, pendingStore);

        tools.createHandoffTicket(
                "USER-001", "conv-repeat", "COMPLAINT", "第一次投诉", "operation-1"
        );
        String firstKey = client.lastRequest.idempotencyKey();
        tools.createHandoffTicket(
                "USER-001", "conv-repeat", "COMPLAINT", "第二次投诉", "operation-2"
        );
        String secondKey = client.lastRequest.idempotencyKey();

        assertNotEquals(firstKey, secondKey);
        assertTrue(firstKey.startsWith("agent-handoff-"));
        assertTrue(secondKey.startsWith("agent-handoff-"));
        assertEquals(2, client.callCount);
        assertEquals(2, pendingStore.size());
    }

    private static class FakeAfterSalesTicketClient implements AfterSalesTicketClient {

        private final AfterSalesTicketCreateResult result;
        private AfterSalesTicketCreateRequest lastRequest;
        private boolean fail;
        private int callCount;

        private FakeAfterSalesTicketClient(AfterSalesTicketCreateResult result) {
            this.result = result;
        }

        @Override
        public AfterSalesTicketCreateResult createTicket(AfterSalesTicketCreateRequest request) {
            callCount++;
            this.lastRequest = request;
            if (fail) {
                throw new IllegalStateException("remote platform unavailable");
            }
            return result;
        }
    }
}
