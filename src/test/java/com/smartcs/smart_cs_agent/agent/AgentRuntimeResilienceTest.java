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
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.testing.WorkflowTestSupport;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Agent 循环预算和失败终态的可靠性测试，不依赖网络或真实模型。 */
class AgentRuntimeResilienceTest {

    @Test
    void shouldStopLoopingWorkflowAtConfiguredStepBudgetAndExposeTechnicalOutcome() {
        SkillManager skillManager = loopingSkillManager();
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
        SkillManager skillManager = loopingSkillManager();
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of(new LoopActionHandler()));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntime(skillManager, registry, 0)
        );

        assertTrue(error.getMessage().contains("max-iterations"));
    }

    @Test
    void shouldStopAfterSlowActionReturnsInsteadOfStartingAnotherStep() {
        SkillManager skillManager = loopingSkillManager();
        AtomicInteger handledCount = new AtomicInteger();
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of(
                new SlowLoopActionHandler(handledCount)
        ));
        // 200ms 足够 Runtime 完成纯内存路由，1 秒睡眠则稳定越过预算，减少慢 CI/GC 导致的竞态。
        AgentRuntime runtime = new AgentRuntime(skillManager, registry, 5, 200);
        ChatRequest request = new ChatRequest("conv-turn-deadline", "USER-001", "慢调用测试");
        ConversationState state = new ConversationState(request.getMessage(), request.getUserId());
        TurnContext context = new TurnContext(request, state);

        String reply = runtime.runTurn(context);

        assertTrue(reply.contains("时间上限"));
        assertEquals(1, handledCount.get(), "慢 Action 返回后不能再执行第二个 Action");
        assertEquals(WorkflowStatus.FAILED, state.getWorkflowStatus());
        TraceEvent deadline = context.getTraceEvents().stream()
                .filter(event -> "turn_deadline_exceeded".equals(event.getEventType()))
                .findFirst()
                .orElseThrow();
        assertEquals(200L, deadline.getData().get("budgetMs"));
    }

    @Test
    void shouldRejectDisabledWallClockBudget() {
        SkillManager skillManager = loopingSkillManager();
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of(new LoopActionHandler()));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntime(skillManager, registry, 3, 0)
        );

        assertTrue(error.getMessage().contains("turn-timeout-ms"));
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

    /** 模拟一个不理解中断、只能在稍后返回的第三方 Action 插件。 */
    private static final class SlowLoopActionHandler implements ActionHandler<LoopAction> {

        private final AtomicInteger handledCount;

        private SlowLoopActionHandler(AtomicInteger handledCount) {
            this.handledCount = handledCount;
        }

        @Override
        public Class<LoopAction> actionType() {
            return LoopAction.class;
        }

        @Override
        public ActionExecutionResult handle(LoopAction action, ActionHandlingContext context) {
            handledCount.incrementAndGet();
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ActionExecutionResult.continueLoop();
        }
    }

    private SkillManager loopingSkillManager() {
        return WorkflowTestSupport.skillManager(new TestSkillConfig(), new LoopingWorkflow());
    }

    private static final class LoopingWorkflow implements AgentWorkflow {

        private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
                "looping_test_workflow",
                "test-v1",
                "测试 Runtime 循环预算",
                100
        );

        @Override
        public WorkflowDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public WorkflowMatchResult match(RoutingContext context) {
            return new WorkflowMatchResult(1.0, "测试固定命中");
        }

        @Override
        public WorkflowDecision nextAction(TurnContext context) {
            return WorkflowDecision.next(new LoopAction(), "持续执行以触发 Runtime 预算");
        }
    }

    private static final class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "test";
        }
    }
}
