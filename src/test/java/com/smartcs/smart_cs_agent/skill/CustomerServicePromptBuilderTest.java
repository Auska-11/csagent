package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prompt 构造器测试。
 *
 * Prompt 不需要逐字完全一致，但必须包含关键约束和工具结果。
 */
class CustomerServicePromptBuilderTest {

    private final CustomerServicePromptBuilder promptBuilder = new CustomerServicePromptBuilder();

    @Test
    void shouldBuildIntentRecognitionPrompt() {
        TurnContext context = turn("帮我查订单 ORD-001");

        String prompt = promptBuilder.buildIntentRecognitionPrompt(context);

        assertTrue(prompt.contains("ORDER_QUERY"));
        assertTrue(prompt.contains("RETURN_APPLY"));
        assertTrue(prompt.contains("帮我查订单 ORD-001"));
    }

    @Test
    void shouldBuildResponseGenerationPromptWithToolResult() {
        TurnContext context = turn("帮我查订单 ORD-001");
        context.addObservation("capability", ToolResult.success(
                "order_lookup",
                "订单 ORD-001 已发货",
                Map.of("order_id", "ORD-001")
        ));

        String prompt = promptBuilder.buildResponseGenerationPrompt(context);

        assertTrue(prompt.contains("不要暴露内部工具名称"));
        assertTrue(prompt.contains("订单 ORD-001 已发货"));
    }

    private TurnContext turn(String message) {
        ConversationState state = new ConversationState(message, "USER-001");
        ChatRequest request = new ChatRequest("conv-prompt-test", "USER-001", message);
        return new TurnContext(request, state);
    }
}
