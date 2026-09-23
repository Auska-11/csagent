package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
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
                "After-sales ticket client is required in remote ticket mode",
                error.getMessage()
        );
        assertEquals(0, pendingStore.size());
    }

    @Test
    void shouldPassTrustedConversationIdToSlaV1Client() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("sla-v1");
        FakeAfterSalesTicketClient client = new FakeAfterSalesTicketClient(
                new AfterSalesTicketCreateResult("AS-321", "PENDING", "2026-09-08T02:00:00Z")
        );
        InMemoryPendingHandoffStore pendingStore = new InMemoryPendingHandoffStore();
        HandoffTools tools = new HandoffTools(properties, client, pendingStore);

        HandoffTicketData ticket = tools.createHandoffTicketForTenant(
                TENANT,
                "USER-XY-001",
                "xianyu_12345_67890",
                HandoffAction.REASON_PRODUCT_VERIFICATION,
                "买家要求核实耳机蓝牙功能",
                null
        );

        // sla-v1 模式必须把可信 conversationId 交给客户端：SLA 建单坐标只能从这里推导。
        assertEquals("xianyu_12345_67890", client.lastConversationId);
        assertEquals(TENANT, client.lastTenantId);
        assertEquals("AS-321", ticket.getTicketId());

        PendingHandoffRecord confirmed = pendingStore
                .findByKey(new PendingHandoffKey(TENANT, client.lastRequest.idempotencyKey()))
                .orElseThrow();
        assertEquals(PendingHandoffStatus.CONFIRMED, confirmed.status());
    }

    @Test
    void shouldNotFallbackToMemoryWhenClientIsMissingInSlaV1Mode() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("sla-v1");
        InMemoryPendingHandoffStore pendingStore = new InMemoryPendingHandoffStore();
        HandoffTools tools = new HandoffTools(properties, null, pendingStore);

        HandoffTicketCreationException error = assertThrows(
                HandoffTicketCreationException.class,
                () -> tools.createHandoffTicketForTenant(
                        TENANT,
                        "USER-XY-001",
                        "xianyu_12345_67890",
                        HandoffAction.REASON_PRODUCT_VERIFICATION,
                        "SLA 未就绪也不能伪造本地成功",
                        "operation-sla-missing"
                )
        );

        assertEquals("After-sales ticket client is required in remote ticket mode", error.getMessage());
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
            public PendingHandoffRecord markNeedsManualReviewByKey(PendingHandoffKey key,
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

    @Test
    void shouldRouteOnlyProductVerificationToSlaV1Client() {
        // 契约第一阶段 SLA 只有 PRODUCT_CHECK 一种事项类型：普通转人工/投诉在 sla-v1
        // 模式必须退回本地工单，只有商品核实才允许远端建单。
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("sla-v1");
        FakeAfterSalesTicketClient client = new FakeAfterSalesTicketClient(
                new AfterSalesTicketCreateResult("WI-777", "PENDING", "2026-09-08T00:00:00Z")
        );
        InMemoryPendingHandoffStore pendingStore = new InMemoryPendingHandoffStore();
        HandoffTools tools = new HandoffTools(properties, client, pendingStore);

        HandoffTicketData ordinary = tools.createHandoffTicketForTenant(
                TENANT,
                "USER-XY-001",
                "xianyu_111_222",
                "COMPLAINT",
                "用户投诉耳机质量，需要人工处理",
                null
        );

        assertTrue(ordinary.getTicketId().startsWith("HD-"),
                "普通投诉退回本地工单: " + ordinary.getTicketId());
        assertEquals("WAITING_HUMAN_AGENT", ordinary.getStatus());
        assertEquals(0, client.callCount, "普通转人工不得调用 SLA 建单");
        assertEquals(0, pendingStore.size(), "本地工单不产生待确认远端记录");

        HandoffTicketData verification = tools.createHandoffTicketForTenant(
                TENANT,
                "USER-XY-001",
                "xianyu_111_222",
                HandoffAction.REASON_PRODUCT_VERIFICATION,
                "买家询问蓝牙功能是否正常，商品标记未测试",
                null
        );

        assertEquals("WI-777", verification.getTicketId(), "商品核实走 SLA v1 远端建单");
        assertEquals(1, client.callCount);
        assertEquals(1, pendingStore.size());
        assertEquals(PendingHandoffStatus.CONFIRMED, pendingStore
                .findByKey(new PendingHandoffKey(TENANT, client.lastRequest.idempotencyKey()))
                .orElseThrow()
                .status());
    }

    @Test
    void shouldExcludeDefinitiveRejectionFromAutomaticRetryScan() {
        // 需求四分类收口：400/403/409 类明确拒绝直接进人工复核并退出自动重试扫描；
        // 对照组可重试失败（超时/5xx 语义）保持 RESULT_UNKNOWN 可被恢复执行器领取。
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("sla-v1");
        FakeAfterSalesTicketClient client = new FakeAfterSalesTicketClient(null);
        InMemoryPendingHandoffStore pendingStore = new InMemoryPendingHandoffStore();
        HandoffTools tools = new HandoffTools(properties, client, pendingStore);

        client.reject = true;
        assertThrows(
                HandoffTicketCreationRejectedException.class,
                () -> tools.createHandoffTicketForTenant(
                        TENANT,
                        "USER-XY-001",
                        "xianyu_111_222",
                        HandoffAction.REASON_PRODUCT_VERIFICATION,
                        "买家询问蓝牙功能是否正常",
                        null
                )
        );

        PendingHandoffRecord rejected = pendingStore
                .findByKey(new PendingHandoffKey(TENANT, client.lastRequest.idempotencyKey()))
                .orElseThrow();
        assertEquals(PendingHandoffStatus.NEEDS_MANUAL_REVIEW, rejected.status(),
                "明确拒绝必须直接进人工复核");
        assertTrue(pendingStore.findDue(Long.MAX_VALUE, 10).isEmpty(),
                "拒绝记录不得进入自动建单重试扫描（即使时间无穷大）");

        client.reject = false;
        client.fail = true;
        assertThrows(
                HandoffTicketCreationException.class,
                () -> tools.createHandoffTicketForTenant(
                        TENANT,
                        "USER-XY-001",
                        "xianyu_333_444",
                        HandoffAction.REASON_PRODUCT_VERIFICATION,
                        "买家询问降噪功能是否正常",
                        null
                )
        );

        assertEquals(1, pendingStore.findDue(Long.MAX_VALUE, 10).size(),
                "可重试失败保持 RESULT_UNKNOWN，仍可被恢复执行器领取");
        assertEquals(2, client.callCount, "两次都真实调用了远端");
    }

    private static class FakeAfterSalesTicketClient implements AfterSalesTicketClient {

        private final AfterSalesTicketCreateResult result;
        private AfterSalesTicketCreateRequest lastRequest;
        private String lastTenantId;
        private String lastConversationId;
        private boolean fail;
        private boolean reject;
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
            if (reject) {
                throw new HandoffTicketCreationRejectedException(
                        "SLA v1 rejected work item creation: 409 VERSION_CONTENT_CONFLICT");
            }
            if (fail) {
                throw new IllegalStateException("remote platform unavailable");
            }
            return result;
        }

        @Override
        public AfterSalesTicketCreateResult createTicket(String tenantId,
                                                          String conversationId,
                                                          AfterSalesTicketCreateRequest request) {
            this.lastConversationId = conversationId;
            return createTicket(tenantId, request);
        }
    }
}
