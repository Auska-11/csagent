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

/** 从真实客服入口验证升级 Package、Workflow、Handler、Gateway 和工单 Provider。 */
@SpringBootTest
class EscalationWorkflowIntegrationTest {

    @Autowired
    private CustomerServiceAgent agent;

    @Autowired
    private ConversationStore conversationStore;

    @Test
    void shouldRouteComplaintToEscalationWorkflowAndCreateTicket() {
        String conversationId = "conv-escalation-integration";
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));

        ChatResponse response = agent.chatWithTrace(request(
                conversationId,
                "我要投诉你们的服务，找人工处理"
        ));

        assertTrue(response.getReply().contains("工单号"), response::getReply);
        assertTrue(hasTraceValue(response, "workflow_routed", "selectedWorkflow", "escalation"));
        assertTrue(hasTraceValue(response, "skill_package_selected", "skillId", "escalation"));
        assertTrue(hasTraceValue(response, "handoff_requested", "reason", "COMPLAINT"));
        assertTrue(hasTraceValue(response, "handoff_created", "providerId", "local-handoff"));
    }

    @Test
    void shouldKeepHandoffPolicyQuestionInPolicyQaWithoutCreatingTicket() {
        String conversationId = "conv-handoff-policy-integration";
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));

        ChatResponse response = agent.chatWithTrace(request(
                conversationId,
                "什么情况可以转人工？"
        ));

        assertTrue(hasTraceValue(response, "workflow_routed", "selectedWorkflow", "policy_qa"));
        assertFalse(hasTraceEvent(response, "handoff_requested"));
        assertFalse(hasTraceEvent(response, "handoff_created"));
        assertTrue(response.getReply().contains("人工"), response::getReply);
    }

    @Test
    void shouldInterruptWaitingWorkflowWhenUserRaisesComplaint() {
        String conversationId = "conv-escalation-interrupt";
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));

        ChatResponse first = agent.chatWithTrace(request(conversationId, "帮我查订单"));
        assertTrue(first.getReply().contains("订单号"), first::getReply);

        ChatResponse second = agent.chatWithTrace(request(conversationId, "一直查不到，我要投诉你们"));

        // Router 先恢复等待中的订单 Workflow，再由共享 GlobalInterruptPolicy 安全打断槽位收集。
        assertTrue(hasTraceValue(second, "workflow_routed", "selectedWorkflow", "order_query"));
        assertTrue(hasTraceValue(second, "handoff_requested", "reason", "COMPLAINT"));
        assertTrue(hasTraceContaining(second, "handoff_requested", "summary", "一直查不到，我要投诉你们"));
        assertTrue(second.getReply().contains("工单号"), second::getReply);
    }

    private ChatRequest request(String conversationId, String message) {
        return new ChatRequest(
                conversationId,
                "USER-001",
                message,
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER"))
        );
    }

    private boolean hasTraceEvent(ChatResponse response, String eventType) {
        return response.getTrace().stream().anyMatch(event -> eventType.equals(event.getEventType()));
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

    private boolean hasTraceContaining(ChatResponse response,
                                       String eventType,
                                       String field,
                                       String expectedPart) {
        return response.getTrace().stream().anyMatch(event -> {
            Object value = event.getData().get(field);
            return eventType.equals(event.getEventType())
                    && value != null
                    && value.toString().contains(expectedPart);
        });
    }
}
