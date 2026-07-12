package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 从真实客服入口验证 ReturnQueryWorkflow、Router、Gateway 和三个 Repository 的完整调用链。 */
@SpringBootTest
class ReturnQueryWorkflowIntegrationTest {

    @Autowired
    private CustomerServiceAgent agent;

    @Autowired
    private ConversationStore conversationStore;

    @Test
    void shouldQueryOwnedReturnAndKeepWorkflowStickyAcrossTurns() {
        String conversationId = "conv-return-query-integration";
        conversationStore.clear(conversationId);

        ChatResponse first = agent.chatWithTrace(request(
                conversationId,
                "USER-002",
                "帮我查退货进度"
        ));
        assertTrue(first.getReply().contains("退货单号"));

        // 第二轮只有编号。Router 应恢复上一轮的 return_query，而不是重新猜测一个新意图。
        ChatResponse second = agent.chatWithTrace(request(
                conversationId,
                "USER-002",
                "RET-001"
        ));

        assertTrue(second.getReply().contains("Type-C 数据线"));
        assertTrue(second.getReply().contains("审核通过"));
        assertTrue(hasTraceValue(second, "workflow_routed", "selectedWorkflow", "return_query"));
        assertTrue(second.getTrace().stream().anyMatch(event ->
                "workflow_routed".equals(event.getEventType())
                        && Boolean.TRUE.equals(event.getData().get("resumed"))
        ));
        assertTrue(hasTraceValue(second, "tool_observed", "capability", "return.lookup"));
    }

    @Test
    void shouldRejectAnotherUsersReturnWithoutLeakingLinkedDetails() {
        String conversationId = "conv-return-query-owner-integration";
        conversationStore.clear(conversationId);

        ChatResponse response = agent.chatWithTrace(request(
                conversationId,
                "USER-001",
                "查询退货进度 RET-001"
        ));

        assertTrue(response.getReply().contains("不属于当前登录用户"));
        assertFalse(response.getReply().contains("ORD-004"));
        assertFalse(response.getReply().contains("Type-C 数据线"));
        assertFalse(response.getReply().contains("数据线接口松动"));
        assertFalse(response.getReply().contains("审核通过"));
    }

    private ChatRequest request(String conversationId, String userId, String message) {
        return new ChatRequest(
                conversationId,
                userId,
                message,
                AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER"))
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
