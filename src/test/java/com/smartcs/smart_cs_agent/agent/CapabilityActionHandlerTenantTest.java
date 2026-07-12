package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.defaults.CapabilityActionHandler;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillDefinition;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.skill.api.RiskLevel;
import com.smartcs.smart_cs_agent.tools.ToolCall;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.escalation.EscalationWorkflow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 普通 Capability 也必须继承会话的 tenant，而不是在 Handler 中写死 default。 */
class CapabilityActionHandlerTenantTest {

    @Test
    void shouldPropagateTenantAndOwnerScopeToExecutionContext() {
        ToolManager toolManager = mock(ToolManager.class);
        SkillManager skillManager = mock(SkillManager.class);
        AgentSkillPackage skillPackage = packageAllowing("knowledge.search");
        when(skillManager.isCapabilityAllowed(skillPackage, "knowledge.search")).thenReturn(true);
        when(toolManager.execute(any(ToolCall.class), any(ExecutionContext.class))).thenReturn(
                ToolResult.fromCapability(
                        true,
                        "knowledge.search",
                        "ok",
                        List.of(),
                        null,
                        CapabilityOutcome.SUCCESS,
                        "test-provider",
                        false
                )
        );

        ChatRequest request = new ChatRequest(
                "tenant-a",
                "shared-conv",
                "USER-001",
                "查询政策",
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER"))
        );
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        EscalationWorkflow workflow = new EscalationWorkflow(new GlobalInterruptPolicy());
        state.startOrResumeWorkflow(workflow.descriptor().getId(), workflow.descriptor().getVersion());
        ActionHandlingContext handlingContext = new ActionHandlingContext(
                new TurnContext(request, state),
                workflow,
                skillPackage
        );

        CapabilityActionHandler handler = new CapabilityActionHandler(toolManager, skillManager);
        handler.handle(new CapabilityAction("knowledge.search", Map.of("query", "退货")), handlingContext);

        ArgumentCaptor<ExecutionContext> contextCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(toolManager).execute(any(ToolCall.class), contextCaptor.capture());
        ExecutionContext executionContext = contextCaptor.getValue();
        assertEquals("tenant-a", executionContext.getTenantId());
        assertEquals("USER-001", executionContext.getPrincipal().getUserId());
        assertEquals("shared-conv", executionContext.getConversationId());
        assertTrue(executionContext.getIdempotencyKey().startsWith(
                "8:tenant-a8:USER-00111:shared-conv:"
        ));
    }

    private AgentSkillPackage packageAllowing(String capability) {
        AgentSkillDefinition definition = new AgentSkillDefinition(
                "tenant-test",
                "1.0.0",
                "租户传播测试",
                List.of("测试"),
                EscalationWorkflow.ID,
                1,
                Set.of(capability),
                "SKILL.md",
                List.of(),
                RiskLevel.READ_ONLY,
                ""
        );
        return new AgentSkillPackage(definition, "# test", Map.of(), "unit-test");
    }
}
