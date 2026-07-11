package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 从 CustomerServiceAgent 入口验证新版退货 Workflow 的完整真实调用链。
 * 这不是只测状态机：它会经过 Router、Runtime、Skill allowlist、CapabilityGateway 和 Repository。
 */
@SpringBootTest
class ReturnApplyWorkflowIntegrationTest {

    @Autowired
    private CustomerServiceAgent agent;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private ReturnRepository returnRepository;

    @Test
    void shouldKeepWorkflowStickyAndSubmitOnlyAfterExplicitConfirmation() {
        String conversationId = "conv-return-v2-integration";
        conversationStore.clear(conversationId);
        assertNull(returnRepository.findByOrderId("ORD-003"));

        ChatResponse first = agent.chatWithTrace(authenticatedRequest(conversationId, "我要退货"));
        assertTrue(first.getReply().contains("订单号"));

        ChatResponse second = agent.chatWithTrace(authenticatedRequest(conversationId, "ORD-003"));
        assertTrue(second.getReply().contains("退货原因"));
        assertTrue(second.getTrace().stream().anyMatch(event ->
                "workflow_routed".equals(event.getEventType())
                        && Boolean.TRUE.equals(event.getData().get("resumed"))
        ));

        ChatResponse third = agent.chatWithTrace(authenticatedRequest(conversationId, "质量问题"));
        assertTrue(third.getReply().contains("确认提交"));
        assertNull(returnRepository.findByOrderId("ORD-003"), "展示确认时还不能写入退货记录");

        ChatResponse fourth = agent.chatWithTrace(authenticatedRequest(conversationId, "确认提交"));
        assertTrue(fourth.getReply().contains("退货申请已提交成功"));

        ReturnRecord record = returnRepository.findByOrderId("ORD-003");
        assertNotNull(record);
        assertEquals("质量问题", record.getReason());

        ConversationState state = conversationStore.get(conversationId);
        assertEquals(WorkflowStatus.COMPLETED, state.getWorkflowStatus());
        assertTrue(state.getActiveWorkflowStateSnapshot().isEmpty());
    }

    private ChatRequest authenticatedRequest(String conversationId, String message) {
        return new ChatRequest(
                conversationId,
                "USER-002",
                message,
                AuthenticatedPrincipal.authenticated("USER-002", Set.of("CUSTOMER"))
        );
    }
}
