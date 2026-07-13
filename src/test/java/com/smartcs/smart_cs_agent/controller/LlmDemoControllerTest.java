package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.llm.LlmExecutionGuard;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 学习用 LLM Demo 也必须经过统一 Guard，不能成为绕过超时和线程上限的旁路。 */
class LlmDemoControllerTest {

    @Test
    void shouldUseGuardAndReturnSafeFallbackWhenModelFails() {
        ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
        LlmExecutionGuard executionGuard = mock(LlmExecutionGuard.class);
        when(executionGuard.generate(any(), any(), any())).thenReturn(
                new LlmExecutionGuard.LlmInvocationResult(
                        false,
                        null,
                        "LLM_PROVIDER_ERROR",
                        5,
                        1_000
                )
        );
        LlmDemoController controller = new LlmDemoController(chatModel, executionGuard, 30_000);

        Map<String, String> response = controller.chat("你好");

        assertEquals("你好", response.get("input"));
        assertEquals("当前大模型服务暂时不可用，请稍后再试。", response.get("reply"));
        verify(executionGuard).generate(any(), any(), any());
    }
}
