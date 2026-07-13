package com.smartcs.smart_cs_agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.defaults.CapabilityActionHandler;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillDefinition;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.skill.api.RiskLevel;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.escalation.EscalationWorkflow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 普通 Capability 也必须继承会话的 tenant，而不是在 Handler 中写死 default。 */
class CapabilityActionHandlerTenantTest {

    @Test
    void shouldPropagateTenantAndOwnerScopeToExecutionContext() throws Exception {
        CapabilityGateway capabilityGateway = mock(CapabilityGateway.class);
        SkillManager skillManager = mock(SkillManager.class);
        AgentSkillPackage skillPackage = packageAllowing("knowledge.search");
        when(skillManager.isCapabilityAllowed(skillPackage, "knowledge.search")).thenReturn(true);
        // invoke 返回 CapabilityResult<?>；doReturn 可避免 Mockito 对通配符做错误的捕获推断。
        doReturn(CapabilityResult.success(
                        "knowledge.search",
                        "test-provider",
                        "provider-result-canary private@example.com",
                        List.of()
                ))
                .when(capabilityGateway).invoke(any(CapabilityCall.class), any(ExecutionContext.class));

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
        TurnContext turnContext = new TurnContext(request, state);
        Instant budgetStartedAt = Instant.now();
        turnContext.startTurnBudget(2_000);
        ActionHandlingContext handlingContext = new ActionHandlingContext(
                turnContext,
                workflow,
                skillPackage
        );

        CapabilityActionHandler handler = new CapabilityActionHandler(capabilityGateway, skillManager);
        Map<String, Object> rawArguments = Map.of(
                "query", "TRACE-QUERY-CANARY token=provider-secret",
                "order_id", "ORD-001"
        );
        handler.handle(new CapabilityAction("knowledge.search", rawArguments), handlingContext);

        ArgumentCaptor<CapabilityCall> callCaptor = ArgumentCaptor.forClass(CapabilityCall.class);
        ArgumentCaptor<ExecutionContext> contextCaptor = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(capabilityGateway).invoke(callCaptor.capture(), contextCaptor.capture());
        ExecutionContext executionContext = contextCaptor.getValue();
        assertEquals("tenant-a", executionContext.getTenantId());
        assertEquals("USER-001", executionContext.getPrincipal().getUserId());
        assertEquals("shared-conv", executionContext.getConversationId());
        assertTrue(executionContext.getDeadline().isAfter(budgetStartedAt));
        assertTrue(executionContext.getDeadline().isBefore(budgetStartedAt.plusSeconds(3)));
        assertTrue(executionContext.getIdempotencyKey().startsWith(
                "8:tenant-a8:USER-00111:shared-conv:"
        ));

        // Provider 仍收到原始参数；只有 Trace 中的副本会被统一清洗。
        assertEquals(rawArguments, callCaptor.getValue().getArguments());
        String traceJson = new ObjectMapper().writeValueAsString(turnContext.getTraceEvents());
        assertFalse(traceJson.contains("TRACE-QUERY-CANARY"));
        assertFalse(traceJson.contains("provider-secret"));
        assertFalse(traceJson.contains("provider-result-canary"));
        assertFalse(traceJson.contains("private@example.com"));
        assertTrue(traceJson.contains("ORD-001"));

        // Handler 只向 Workflow 暴露传输无关的 Observation，不能把 Provider 结果对象直接泄漏出去。
        assertTrue(turnContext.getLatestObservationByType("capability_result").getData()
                instanceof CapabilityObservation);
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
