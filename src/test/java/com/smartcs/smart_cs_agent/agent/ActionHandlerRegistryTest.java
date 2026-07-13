package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.core.ActionHandlerRegistry;
import com.smartcs.smart_cs_agent.config.SkillConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证新 Action 能通过注册 Handler 扩展，而无需修改 AgentRuntime。
 */
class ActionHandlerRegistryTest {

    @Test
    void shouldExecuteCustomActionThroughUnchangedRuntimeLoop() {
        SkillManager skillManager = WorkflowTestSupport.skillManager(
                new TestSkillConfig(),
                new CustomActionWorkflow()
        );
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of(new CustomActionHandler()));
        AgentRuntime runtime = new AgentRuntime(skillManager, registry);

        ChatRequest request = new ChatRequest("conv-custom-action", "USER-001", "执行自定义动作");
        TurnContext context = new TurnContext(
                request,
                new ConversationState(request.getMessage(), request.getUserId())
        );

        String answer = runtime.runTurn(context);

        assertEquals("custom action handled: demo", answer);
        assertTrue(context.getTraceEvents().stream().anyMatch(event ->
                "action_planned".equals(event.getEventType())
                        && "CustomAction".equals(event.getData().get("actionType"))
        ));
    }

    @Test
    void shouldRejectDuplicateHandlersForSameActionType() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ActionHandlerRegistry(List.of(
                        new CustomActionHandler(),
                        new CustomActionHandler()
                ))
        );
        assertTrue(error.getMessage().contains("Duplicate ActionHandler"));
    }

    @Test
    void shouldFailClearlyForActionWithoutHandler() {
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of());
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> registry.describe(new CustomAction("missing"))
        );
        assertTrue(error.getMessage().contains("No ActionHandler registered"));
    }

    @Test
    void shouldConvertUnexpectedPluginExceptionIntoSafeObservationAndReply() {
        SkillManager skillManager = WorkflowTestSupport.skillManager(
                new TestSkillConfig(),
                new CustomActionWorkflow()
        );
        ActionHandlerRegistry registry = new ActionHandlerRegistry(List.of(new ThrowingCustomActionHandler()));
        AgentRuntime runtime = new AgentRuntime(skillManager, registry);
        ChatRequest request = new ChatRequest("conv-failing-plugin", "USER-001", "执行失败插件");
        TurnContext context = new TurnContext(
                request,
                new ConversationState(request.getMessage(), request.getUserId())
        );

        String answer = runtime.runTurn(context);

        assertTrue(answer.contains("暂时失败"));
        assertTrue(context.getTraceEvents().stream()
                .anyMatch(event -> "action_handler_failed".equals(event.getEventType())));
        assertTrue(context.getObservations().stream()
                .anyMatch(observation -> "action_failure".equals(observation.getType())));
        assertEquals(WorkflowStatus.FAILED, context.getState().getWorkflowStatus());
        assertTrue(context.getTraceEvents().stream().anyMatch(event ->
                "workflow_completed".equals(event.getEventType())
                        && "FAILED".equals(event.getData().get("status"))
        ));
        assertTrue(!answer.contains("database password"), "内部异常文本不能进入用户回复");
        assertTrue(context.getTraceEvents().stream()
                .filter(event -> "action_handler_failed".equals(event.getEventType()))
                .map(event -> String.valueOf(event.getData().get("internalMessage")))
                .noneMatch(message -> message.contains("should-never-reach-user")),
                "内部异常原文也不能进入 Trace");
    }

    private static final class CustomAction implements AgentAction {
        private final String value;

        private CustomAction(String value) {
            this.value = value;
        }
    }

    private static final class CustomActionHandler implements ActionHandler<CustomAction> {

        @Override
        public Class<CustomAction> actionType() {
            return CustomAction.class;
        }

        @Override
        public ActionExecutionResult handle(CustomAction action, ActionHandlingContext context) {
            context.getTurnContext().addTrace("custom_action_executed", "测试插件已执行");
            return ActionExecutionResult.completed("custom action handled: " + action.value);
        }

        @Override
        public Map<String, Object> describe(CustomAction action) {
            return Map.of("actionType", "CustomAction", "value", action.value);
        }
    }

    private static final class ThrowingCustomActionHandler implements ActionHandler<CustomAction> {

        @Override
        public Class<CustomAction> actionType() {
            return CustomAction.class;
        }

        @Override
        public ActionExecutionResult handle(CustomAction action, ActionHandlingContext context) {
            throw new IllegalStateException("database password=should-never-reach-user");
        }
    }

    private static final class CustomActionWorkflow implements AgentWorkflow {

        private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
                "custom_action_workflow",
                "test-v1",
                "测试自定义 Action 插件",
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
            return WorkflowDecision.next(new CustomAction("demo"), "验证自定义 Action Handler");
        }
    }

    private static final class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "test";
        }
    }
}
