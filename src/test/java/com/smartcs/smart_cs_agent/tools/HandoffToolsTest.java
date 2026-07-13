package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.handoff.outbox.InMemoryPendingHandoffStore;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffClaim;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffKey;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffPreparation;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandoffToolsTest {

    private static final String TENANT = "tenant-a";

    @Test
    void shouldCreateMemoryTicketByDefault() {
        HandoffTools tools = new HandoffTools();

        HandoffTicketData ticket = tools.createHandoffTicketForTenant(
                TENANT,
                "USER-001",
                "conv-memory",
                "USER_REQUESTED_HUMAN",
                "用户要求人工客服继续处理",
                null
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

        HandoffTicketData ticket = tools.createHandoffTicketForTenant(
                TENANT,
                "USER-001",
                "conv-001",
                "COMPLAINT",
                "用户投诉蓝牙耳机坏了，希望尽快赔偿",
                null
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
                .findByKey(new PendingHandoffKey(TENANT, request.idempotencyKey()))
                .orElseThrow();
        assertEquals(TENANT, confirmed.tenantId());
        assertEquals(TENANT, client.lastTenantId);
        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
        assertEquals("AS-123", confirmed.confirmedResult().ticketId());

        // 模拟 Capability 幂等结果尚未落库后的重试：Outbox 会直接回放，不再调用远端。
        HandoffTicketData replay = tools.createHandoffTicketForTenant(
                TENANT,
                "USER-001",
                "conv-001",
                "COMPLAINT",
                "用户投诉蓝牙耳机坏了，希望尽快赔偿",
                null
        );
        assertEquals("AS-123", replay.getTicketId());
        assertEquals(1, client.callCount);
    }

    @Test
    void shouldNotFallbackToMemoryWhenAfterSalesClientIsMissing() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("after-sales-http");
        InMemoryPendingHandoffStore pendingStore = new InMemoryPendingHandoffStore();
        HandoffTools tools = new HandoffTools(properties, null, pendingStore);

        HandoffTicketCreationException error = assertThrows(
                HandoffTicketCreationException.class,
                () -> tools.createHandoffTicketForTenant(
                        TENANT,
                        "USER-001",
                        "conv-missing-client",
                        "COMPLAINT",
                        "缺少远端 Client 时不能伪造本地成功",
                        "operation-missing-client"
                )
        );

        assertEquals(
                "After-sales ticket client is required in after-sales HTTP mode",
                error.getMessage()
        );
        assertEquals(0, pendingStore.size());
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
                () -> tools.createHandoffTicketForTenant(
                        TENANT,
                        "USER-001",
                        "conv-fallback",
                        "COMPLAINT",
                        "用户投诉，需要人工处理",
                        null
                )
        );

        assertEquals("Remote handoff result could not be confirmed", error.getMessage());
        assertFalse(error.getMessage().contains("remote platform unavailable"));

        PendingHandoffRecord pending = pendingStore
                .findByKey(new PendingHandoffKey(TENANT, client.lastRequest.idempotencyKey()))
                .orElseThrow();
        assertEquals("conv-fallback", pending.conversationId());
        assertEquals("IllegalStateException", pending.failureType());
        assertEquals(PendingHandoffStatus.RESULT_UNKNOWN, pending.status());
        assertEquals(1, pendingStore.size());

        HandoffTicketCreationException replayError = assertThrows(
                HandoffTicketCreationException.class,
                () -> tools.createHandoffTicketForTenant(
                        TENANT,
                        "USER-001",
                        "conv-fallback",
                        "COMPLAINT",
                        "用户投诉，需要人工处理",
                        null
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
            public PendingHandoffRecord markResultUnknown(PendingHandoffKey key,
                                                          String failureType,
                                                          long expectedVersion) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PendingHandoffRecord confirm(PendingHandoffKey key,
                                                AfterSalesTicketCreateResult result,
                                                long expectedVersion) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<PendingHandoffRecord> findDue(long now, int limit) {
                return List.of();
            }

            @Override
            public Optional<PendingHandoffClaim> tryClaim(PendingHandoffKey key,
                                                          long expectedVersion,
                                                          String ownerToken,
                                                          long now,
                                                          long leaseMs) {
                return Optional.empty();
            }

            @Override
            public PendingHandoffRecord scheduleRetry(PendingHandoffClaim claim,
                                                       String failureType,
                                                       long nextAttemptAt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PendingHandoffRecord markNeedsManualReview(PendingHandoffClaim claim,
                                                              String failureType) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void releaseClaim(PendingHandoffClaim claim) {
            }

            @Override
            public Optional<PendingHandoffRecord> findByKey(PendingHandoffKey key) {
                return Optional.empty();
            }
        };
        HandoffTools tools = new HandoffTools(properties, client, unavailableStore);

        HandoffTicketCreationException error = assertThrows(
                HandoffTicketCreationException.class,
                () -> tools.createHandoffTicketForTenant(
                        TENANT,
                        "USER-001",
                        "conv-double-failure",
                        "COMPLAINT",
                        "用户投诉，需要人工处理",
                        null
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

        tools.createHandoffTicketForTenant(
                TENANT, "USER-001", "conv-repeat", "COMPLAINT", "第一次投诉", "operation-1"
        );
        String firstKey = client.lastRequest.idempotencyKey();
        tools.createHandoffTicketForTenant(
                TENANT, "USER-001", "conv-repeat", "COMPLAINT", "第二次投诉", "operation-2"
        );
        String secondKey = client.lastRequest.idempotencyKey();

        assertNotEquals(firstKey, secondKey);
        assertTrue(firstKey.startsWith("agent-handoff-"));
        assertTrue(secondKey.startsWith("agent-handoff-"));
        assertEquals(2, client.callCount);
        assertEquals(2, pendingStore.size());
    }

    @Test
    void shouldUseDifferentRemoteKeysForSameOperationInDifferentTenants() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("after-sales-http");
        FakeAfterSalesTicketClient client = new FakeAfterSalesTicketClient(
                new AfterSalesTicketCreateResult("AS-201", "PENDING", "2026-07-13T00:00:00Z")
        );
        InMemoryPendingHandoffStore pendingStore = new InMemoryPendingHandoffStore();
        HandoffTools tools = new HandoffTools(properties, client, pendingStore);

        tools.createHandoffTicketForTenant(
                "tenant-a", "USER-001", "conv-shared", "COMPLAINT", "相同摘要", "operation-shared"
        );
        String tenantAKey = client.lastRequest.idempotencyKey();
        tools.createHandoffTicketForTenant(
                "tenant-b", "USER-001", "conv-shared", "COMPLAINT", "相同摘要", "operation-shared"
        );
        String tenantBKey = client.lastRequest.idempotencyKey();

        assertNotEquals(tenantAKey, tenantBKey, "租户必须参与远端幂等键哈希");
        assertEquals("tenant-b", client.lastTenantId);
        assertEquals(2, pendingStore.size());
    }

    private static class FakeAfterSalesTicketClient implements AfterSalesTicketClient {

        private final AfterSalesTicketCreateResult result;
        private AfterSalesTicketCreateRequest lastRequest;
        private String lastTenantId;
        private boolean fail;
        private int callCount;

        private FakeAfterSalesTicketClient(AfterSalesTicketCreateResult result) {
            this.result = result;
        }

        @Override
        public AfterSalesTicketCreateResult createTicket(String tenantId,
                                                          AfterSalesTicketCreateRequest request) {
            callCount++;
            this.lastTenantId = tenantId;
            this.lastRequest = request;
            if (fail) {
                throw new IllegalStateException("remote platform unavailable");
            }
            return result;
        }
    }
}
