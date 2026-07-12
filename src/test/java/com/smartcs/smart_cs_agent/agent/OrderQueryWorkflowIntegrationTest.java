package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 从真实 Agent 入口验证订单 Workflow、Router、Gateway 和 Repository 的完整链路。 */
@SpringBootTest
class OrderQueryWorkflowIntegrationTest {

    @Autowired
    private CustomerServiceAgent agent;

    @Autowired
    private ConversationStore conversationStore;

    @Test
    void shouldQueryOwnedOrderAndKeepMultiTurnSticky() {
        String conversationId = "conv-order-query-integration";
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));

        ChatResponse first = agent.chatWithTrace(request(conversationId, "帮我查订单"));
        assertTrue(first.getReply().contains("订单号"));

        ChatResponse second = agent.chatWithTrace(request(conversationId, "ORD-001"));
        assertTrue(second.getReply().contains("蓝牙耳机 Pro Max"));
        assertTrue(second.getReply().contains("SF1234567890"));
        assertTrue(hasTraceValue(second, "workflow_routed", "selectedWorkflow", "order_query"));
        assertTrue(second.getTrace().stream().anyMatch(event ->
                "workflow_routed".equals(event.getEventType())
                        && Boolean.TRUE.equals(event.getData().get("resumed"))
        ));
    }

    @Test
    void shouldListOnlyAuthenticatedUsersOrders() {
        String conversationId = "conv-order-list-integration";
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));

        ChatResponse response = agent.chatWithTrace(request(conversationId, "查看我的全部订单"));

        assertTrue(response.getReply().contains("共有 2 个订单"));
        assertTrue(response.getReply().contains("ORD-001"));
        assertTrue(response.getReply().contains("ORD-002"));
        assertFalse(response.getReply().contains("ORD-003"));
        assertTrue(hasTraceValue(response, "tool_observed", "capability", "order.list-by-user"));
    }

    @Test
    void shouldRejectAnotherUsersOrderWithoutLeakingDetails() {
        String conversationId = "conv-order-owner-integration";
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));

        ChatResponse response = agent.chatWithTrace(request(conversationId, "查询订单 ORD-003"));

        assertTrue(response.getReply().contains("不属于当前登录用户"));
        assertFalse(response.getReply().contains("USB-C 数据线"));
        assertFalse(response.getReply().contains("YT9876543210"));
    }

    private ChatRequest request(String conversationId, String message) {
        return new ChatRequest(
                conversationId,
                "USER-001",
                message,
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER"))
        );
    }

    private boolean hasTraceValue(ChatResponse response,
                                  String eventType,
                                  String field,
                                  String expected) {
        return response.getTrace().stream().anyMatch(event ->
                eventType.equals(event.getEventType())
                        && expected.equals(event.getData().get(field))
        );
    }
}
