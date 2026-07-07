package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.tools.ToolResult;

/**
 * 客服 Prompt 构造器。
 *
 * 【为什么要拆 PromptBuilder？】
 * Prompt 本质上是“如何把上下文组织给 LLM 看”，
 * 它和规则路由、槽位抽取不是同一种职责。
 *
 * 拆出来以后：
 * - CustomerServiceSkill 更专注于 Action 编排。
 * - 后续调整 Prompt 不需要翻很长的 Skill 类。
 * - 如果要做 A/B Prompt 或多语言 Prompt，也有独立入口。
 */
public class CustomerServicePromptBuilder {

    /**
     * 构造意图识别 Prompt。
     *
     * 要求 LLM 只返回一个意图名，是为了让后续代码更容易解析。
     * 如果让 LLM 返回大段解释，程序就很难稳定处理。
     */
    public String buildIntentRecognitionPrompt(TurnContext context) {
        return """
                You are an intent classifier for an ecommerce customer service agent.
                Reply with exactly one intent name only:
                ORDER_QUERY, PRODUCT_QUERY, RETURN_APPLY, RETURN_QUERY, KNOWLEDGE_QUERY, HANDOFF, USER_INFO, CHITCHAT, OTHER.

                Use KNOWLEDGE_QUERY for policy or FAQ questions, such as return policy, warranty, invoice,
                shipping policy, delivery limitation, or human handoff rule.
                Use HANDOFF when the user asks for a human agent, complains strongly, asks for compensation,
                threatens legal action, or the issue should not be handled automatically.

                User message: %s
                """.formatted(context.getMessage());
    }

    /**
     * 构造闲聊回复 Prompt。
     */
    public String buildChitchatPrompt(String message) {
        return "你是电商客服小智，请用中文友好、简洁地回复用户。用户：" + message;
    }

    /**
     * 构造工具结果总结 Prompt。
     */
    public String buildResponseGenerationPrompt(TurnContext context) {
        Observation toolResult = context.getLatestObservationByType("capability");
        String resultStr = toolResult != null ? formatToolResultForPrompt(toolResult.getData()) : "No result";
        Object usedPreference = context.getState().getTaskState("used_user_preference");
        String preferenceContext = usedPreference == null
                ? "无"
                : "本轮商品推荐参考了用户长期偏好：" + usedPreference;
        return """
                你是电商客服小智。请根据下面的工具结果，用中文给用户一个友好、专业、简洁的回复。
                不要暴露内部工具名称。
                如果使用了用户长期偏好，可以自然说明“根据你之前的偏好”。

                用户长期记忆：
                %s

                工具结果：
                %s
                """.formatted(preferenceContext, resultStr);
    }

    /**
     * 把工具结果格式化成适合 LLM 阅读的文本。
     */
    private String formatToolResultForPrompt(Object result) {
        if (result instanceof ToolResult) {
            return ((ToolResult) result).toPromptText();
        }
        return result == null ? "No result" : result.toString();
    }
}
