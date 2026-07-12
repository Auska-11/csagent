package com.smartcs.smart_cs_agent.tools;

import org.junit.jupiter.api.Test;

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
        HandoffTools tools = new HandoffTools(properties, client);

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
        assertEquals("agent:conv-001:handoff:COMPLAINT", request.idempotencyKey());
        assertTrue(request.title().startsWith("Agent 转人工："));
        assertTrue(request.description().contains("会话ID：conv-001"));
    }

    @Test
    void shouldNotFabricatePendingTicketWhenAfterSalesPlatformFails() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("after-sales-http");
        FakeAfterSalesTicketClient client = new FakeAfterSalesTicketClient(null);
        client.fail = true;
        HandoffTools tools = new HandoffTools(properties, client);

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
    }

    private static class FakeAfterSalesTicketClient implements AfterSalesTicketClient {

        private final AfterSalesTicketCreateResult result;
        private AfterSalesTicketCreateRequest lastRequest;
        private boolean fail;

        private FakeAfterSalesTicketClient(AfterSalesTicketCreateResult result) {
            this.result = result;
        }

        @Override
        public AfterSalesTicketCreateResult createTicket(AfterSalesTicketCreateRequest request) {
            this.lastRequest = request;
            if (fail) {
                throw new IllegalStateException("remote platform unavailable");
            }
            return result;
        }
    }
}
