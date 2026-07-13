package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.LLMAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionFlow;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.defaults.LlmActionHandler;
import com.smartcs.smart_cs_agent.agent.llm.LlmExecutionGuard;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** LLM Handler 的流程控制测试，重点保证线程中断后不会继续启动下一步 Action。 */
class LlmActionHandlerTest {

    @Test
    void shouldTerminateWorkflowWhenLlmWaitingThreadWasInterrupted() {
        SkillManager skillManager = new SkillManager(new TestSkillConfig());
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        LlmExecutionGuard executionGuard = mock(LlmExecutionGuard.class);
        when(executionGuard.generate(any(), any(), any())).thenReturn(
                new LlmExecutionGuard.LlmInvocationResult(
                        false,
                        null,
                        "LLM_EXECUTION_INTERRUPTED",
                        12,
                        1_000
                )
        );
        LlmActionHandler handler = new LlmActionHandler(skillManager, chatModel, executionGuard);

        ChatRequest request = new ChatRequest("conv-llm-interrupted", "USER-001", "测试中断");
        TurnContext turn = new TurnContext(
                request,
                new ConversationState(request.getMessage(), request.getUserId())
        );
        ActionHandlingContext handlingContext = new ActionHandlingContext(
                turn,
                mock(AgentWorkflow.class),
                null
        );

        ActionExecutionResult result = handler.handle(
                new LLMAction("intent_recognition", "识别意图", Map.of()),
                handlingContext
        );

        assertEquals(ActionFlow.FAILED, result.getFlow());
        assertTrue(result.getAnswer().contains("中断"));
        assertTrue(turn.getTraceEvents().stream().anyMatch(event ->
                "llm_failed".equals(event.getEventType())
                        && "LLM_EXECUTION_INTERRUPTED".equals(event.getData().get("errorCode"))
        ));
    }

    private static final class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "test system prompt";
        }
    }
}
