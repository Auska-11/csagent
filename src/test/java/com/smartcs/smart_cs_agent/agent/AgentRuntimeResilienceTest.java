package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.core.ActionHandlerRegistry;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.MonitorSummary;
import com.smartcs.smart_cs_agent.monitor.TurnOutcome;
import com.smartcs.smart_cs_agent.skill.Skill;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agent 循环预算和失败终态的可靠性测试，不依赖网络或真实模型。 */
class AgentRuntimeResilienceTest {

    @Test
    void shouldStopLoopingWorkflowAtConfiguredStepBudgetAndExposeTechnicalOutcome() {
        SkillManager skillManager = new SkillManager(new TestSkillConfig());
        skillManager.registerSkill(new LoopingSkill());
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of(new LoopActionHandler()));
        AgentRuntime runtime = new AgentRuntime(skillManager, registry, 3);
        ChatRequest request = new ChatRequest("conv-loop-budget", "USER-001", "循环测试");
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        TurnContext context = new TurnContext(request, state);

        String reply = runtime.runTurn(context);

        assertTrue(reply.contains("处理步骤过多"));
        assertEquals(WorkflowStatus.FAILED, state.getWorkflowStatus());
        assertFalse(state.hasActiveNonTerminalWorkflow());
        TraceEvent timeout = context.getTraceEvents().stream()
                .filter(event -> "turn_timeout".equals(event.getEventType()))
                .findFirst()
                .orElseThrow();
        assertEquals(3, timeout.getData().get("maxIterations"));
        assertTrue(context.getTraceEvents().stream().anyMatch(event ->
                "workflow_completed".equals(event.getEventType())
                        && "FAILED".equals(event.getData().get("status"))
        ));

        // Monitor 继续只消费结构化 Trace，不需要识别上面的中文降级回复。
        MonitorService monitorService = new MonitorService();
        monitorService.recordTurn(new ChatResponse(
                request.getConversationId(),
                request.getUserId(),
                reply,
                context.getTraceEvents()
        ));
        MonitorSummary summary = monitorService.getSummary();
        assertEquals(1, summary.getTechnicalFailureTurnCount());
        assertEquals(TurnOutcome.TECHNICAL_FAILURE, summary.getRecentTurns().get(0).getOutcome());
        assertEquals("TURN_STEP_BUDGET_EXCEEDED", summary.getRecentTurns().get(0).getErrorCode());
    }

    @Test
    void shouldRejectDisabledStepBudget() {
        SkillManager skillManager = new SkillManager(new TestSkillConfig());
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of(new LoopActionHandler()));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntime(skillManager, registry, 0)
        );

        assertTrue(error.getMessage().contains("max-iterations"));
    }

    /** 测试 Action 本身没有业务含义，只用于模拟扩展插件始终要求继续循环。 */
    private static final class LoopAction implements AgentAction {
    }

    private static final class LoopActionHandler implements ActionHandler<LoopAction> {

        @Override
        public Class<LoopAction> actionType() {
            return LoopAction.class;
        }

        @Override
        public ActionExecutionResult handle(LoopAction action, ActionHandlingContext context) {
            return ActionExecutionResult.continueLoop();
        }

        @Override
        public Map<String, Object> describe(LoopAction action) {
            return Map.of("actionType", "LoopAction");
        }
    }

    private static final class LoopingSkill implements Skill {

        @Override
        public AgentAction nextAction(TurnContext context) {
            return new LoopAction();
        }

        @Override
        public String getName() {
            return "looping_test_workflow";
        }

        @Override
        public float canHandle(String message) {
            return 1.0f;
        }
    }

    private static final class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "test";
        }
    }
}
