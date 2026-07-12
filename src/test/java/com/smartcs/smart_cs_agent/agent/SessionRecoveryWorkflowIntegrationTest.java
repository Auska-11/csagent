package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSkill;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.session.memory.InMemorySessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实 Router/Runtime/Capability 链验证 ConversationStore 重建后的 Workflow 恢复。 */
@SpringBootTest
class SessionRecoveryWorkflowIntegrationTest {

    @Autowired
    private AgentRuntime runtime;

    @Autowired
    private SkillManager skillManager;

    @Autowired
    private CustomerServiceSkill customerServiceSkill;

    @Test
    void shouldResumeReturnWorkflowAfterConversationStoreRecreation() {
        InMemorySessionStore durableBackend = new InMemorySessionStore();
        String conversationId = "session-recovery-return";
        ConversationKey key = ConversationKey.defaultTenant("USER-002", conversationId);

        CustomerServiceAgent firstProcess = agentWith(new ConversationStore(durableBackend));
        ChatResponse first = firstProcess.chatWithTrace(request(conversationId, "我要退货"));
        assertTrue(first.getReply().contains("订单号"), first::getReply);
        long workflowStartedAt = state(durableBackend, key).getWorkflowStartedAt();

        CustomerServiceAgent secondProcess = agentWith(new ConversationStore(durableBackend));
        ChatResponse second = secondProcess.chatWithTrace(request(conversationId, "ORD-003"));
        assertTrue(second.getReply().contains("退货原因"), second::getReply);
        assertTrue(second.getTrace().stream().anyMatch(event ->
                "workflow_routed".equals(event.getEventType())
                        && Boolean.TRUE.equals(event.getData().get("resumed"))
        ));

        CustomerServiceAgent thirdProcess = agentWith(new ConversationStore(durableBackend));
        ChatResponse third = thirdProcess.chatWithTrace(request(conversationId, "质量问题"));
        assertTrue(third.getReply().contains("确认提交"), third::getReply);

        ConversationState restored = state(durableBackend, key);
        assertEquals(workflowStartedAt, restored.getWorkflowStartedAt());
        assertEquals("WAITING_CONFIRMATION", restored.getWorkflowState("return_apply", "step"));
        assertEquals("ORD-003", restored.getWorkflowState("return_apply", "orderId"));
        assertEquals("质量问题", restored.getWorkflowState("return_apply", "reason"));
        assertEquals(3, durableBackend.load(key).orElseThrow().version());
    }

    private CustomerServiceAgent agentWith(ConversationStore store) {
        return new CustomerServiceAgent(runtime, skillManager, customerServiceSkill, store);
    }

    private ChatRequest request(String conversationId, String message) {
        return new ChatRequest(
                conversationId,
                "USER-002",
                message,
                AuthenticatedPrincipal.authenticated("USER-002", Set.of("CUSTOMER"))
        );
    }

    private ConversationState state(InMemorySessionStore store, ConversationKey key) {
        return ConversationState.restore(store.load(key).orElseThrow().snapshot());
    }
}
