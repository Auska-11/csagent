package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionFlow;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.defaults.HandoffActionHandler;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.contracts.HandoffTicketSummary;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** HandoffActionHandler 的 Skill allowlist 和服务端 ExecutionContext 测试。 */
class HandoffActionHandlerTest {

    @Test
    void shouldDenyHandoffWhenSelectedSkillDidNotDeclareCapability() {
        ToolManager toolManager = mock(ToolManager.class);
        SkillManager skillManager = mock(SkillManager.class);
        AgentSkillPackage skillPackage = packageAllowing(Set.of("order.lookup"));
        when(skillManager.isCapabilityAllowed(skillPackage, CapabilityIds.HANDOFF_REQUEST)).thenReturn(false);
        HandoffActionHandler handler = new HandoffActionHandler(toolManager, skillManager);

        ActionExecutionResult result = handler.handle(action(), handlingContext(skillPackage));

        assertEquals(ActionFlow.COMPLETED, result.getFlow());
        assertTrue(result.getAnswer().contains("没有创建人工工单的权限"));
        verifyNoInteractions(toolManager);
    }

    @Test
    void shouldCallCapabilityWithOnlyBusinessArgumentsAndTrustedContext() {
        ToolManager toolManager = mock(ToolManager.class);
        SkillManager skillManager = mock(SkillManager.class);
        AgentSkillPackage skillPackage = packageAllowing(Set.of(CapabilityIds.HANDOFF_REQUEST));
        when(skillManager.isCapabilityAllowed(skillPackage, CapabilityIds.HANDOFF_REQUEST)).thenReturn(true);
        when(toolManager.execute(any(ToolCall.class), any(ExecutionContext.class))).thenReturn(
                ToolResult.fromCapability(
                        true,
                        CapabilityIds.HANDOFF_REQUEST,
                        "created",
                        new HandoffTicketSummary(
                                "HD-3001",
                                "COMPLAINT",
                                "WAITING_HUMAN_AGENT",
                                "2026-07-12T12:00:00"
                        ),
                        null,
                        CapabilityOutcome.SUCCESS,
                        "local-handoff",
                        false
                )
        );
        HandoffActionHandler handler = new HandoffActionHandler(toolManager, skillManager);

        ActionExecutionResult result = handler.handle(action(), handlingContext(skillPackage));

        assertEquals(ActionFlow.HANDED_OFF, result.getFlow());
        assertTrue(result.getAnswer().contains("HD-3001"));

        ArgumentCaptor<ToolCall> callCaptor = ArgumentCaptor.forClass(ToolCall.class);
        ArgumentCaptor<ExecutionContext> contextCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(toolManager).execute(callCaptor.capture(), contextCaptor.capture());
        assertEquals(Set.of("reason", "summary"), callCaptor.getValue().getArguments().keySet());
        assertFalse(callCaptor.getValue().getArguments().containsKey("user_id"));
        assertEquals("USER-001", contextCaptor.getValue().getPrincipal().getUserId());
        assertEquals("tenant-demo", contextCaptor.getValue().getTenantId());
        assertEquals("conv-handler", contextCaptor.getValue().getConversationId());
        assertTrue(contextCaptor.getValue().getIdempotencyKey().startsWith(
                "11:tenant-demo8:USER-00112:conv-handler:"
        ));
    }

    private HandoffAction action() {
        return new HandoffAction("COMPLAINT", "用户投诉服务问题", Map.of());
    }

    private ActionHandlingContext handlingContext(AgentSkillPackage skillPackage) {
        ChatRequest request = new ChatRequest(
                "tenant-demo",
                "conv-handler",
                "USER-001",
                "我要投诉",
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER"))
        );
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        state.startOrResumeWorkflow(EscalationWorkflow.ID, EscalationWorkflow.VERSION);
        TurnContext turn = new TurnContext(request, state);
        return new ActionHandlingContext(
                turn,
                new EscalationWorkflow(new GlobalInterruptPolicy()),
                skillPackage
        );
    }

    private AgentSkillPackage packageAllowing(Set<String> capabilities) {
        AgentSkillDefinition definition = new AgentSkillDefinition(
                "handler-test",
                "1.0.0",
                "人工升级 Handler 测试",
                List.of("投诉"),
                EscalationWorkflow.ID,
                1,
                capabilities,
                "SKILL.md",
                List.of(),
                RiskLevel.SIDE_EFFECT,
                "explicit-escalation-intent"
        );
        return new AgentSkillPackage(definition, "# test", Map.of(), "unit-test");
    }
}
