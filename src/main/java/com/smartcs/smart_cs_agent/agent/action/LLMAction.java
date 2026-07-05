package com.smartcs.smart_cs_agent.agent.action;

import java.util.Map;

/**
 * LLM 调用 Action
 *
 * 【作用】
 * 当 Skill 需要调用 LLM 时，返回这个 Action。
 * 比如意图识别、回复生成等。
 *
 * 【使用场景】
 * 1. 意图识别："请识别用户意图"
 * 2. 回复生成："根据结果生成回复"
 * 3. 对话摘要："压缩历史对话"（后续）
 * 4. 事实提取："提取关键信息"（后续）
 *
 * 【字段说明】
 * - purpose：用途，标识这次 LLM 调用是做什么的
 *   - intent_recognition：意图识别
 *   - response_generation：回复生成
 *   - 后续可以扩展更多用途
 * - prompt：提示词，发给 LLM 的内容
 * - context：上下文信息，比如用户消息、历史对话等
 *
 * 【扩展性】
 * purpose 是字符串类型，后续扩展不需要改代码，直接传新值即可。
 */
public class LLMAction implements AgentAction {

    /** 用途：标识这次 LLM 调用是做什么的 */
    private final String purpose;

    /** 提示词：发给 LLM 的内容 */
    private final String prompt;

    /** 上下文信息：比如用户消息、历史对话等 */
    private final Map<String, Object> context;

    /**
     * 构造函数
     *
     * @param purpose 用途，如 intent_recognition, response_generation
     * @param prompt 提示词
     * @param context 上下文信息
     */
    public LLMAction(String purpose, String prompt, Map<String, Object> context) {
        this.purpose = purpose;
        this.prompt = prompt;
        this.context = context;
    }

    /** 获取用途 */
    public String getPurpose() {
        return purpose;
    }

    /** 获取提示词 */
    public String getPrompt() {
        return prompt;
    }

    /** 获取上下文信息 */
    public Map<String, Object> getContext() {
        return context;
    }

    @Override
    public String toString() {
        return "LLMAction{" +
                "purpose=" + purpose +
                ", prompt=" + prompt.substring(0, Math.min(50, prompt.length())) + "..." +
                "}";
    }
}