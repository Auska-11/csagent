package com.smartcs.smart_cs_agent.agent.action;

import java.util.Collections;
import java.util.Map;

/**
 * 转人工 Action。
 *
 * 【为什么单独设计 HandoffAction？】
 * 转人工不是普通工具调用，也不是让 LLM 随便回复一句“我帮你转人工”。
 * 它表示 Agent 做出了一个明确决策：当前问题不适合继续自动处理，需要人工客服介入。
 *
 * 这样做的好处：
 * - Runtime 可以统一记录转人工 Trace
 * - 后续评测可以判断“是否应该转人工”
 * - 后续可以接真实工单系统或人工客服系统
 */
public class HandoffAction implements AgentAction {

    /** 转人工原因，例如 USER_REQUESTED_HUMAN、COMPLAINT、COMPENSATION_OR_DISPUTE */
    private final String reason;

    /** 给人工客服看的问题摘要 */
    private final String summary;

    /** 额外上下文，例如 userId、conversationId、activeIntent 等 */
    private final Map<String, Object> context;

    public HandoffAction(String reason, String summary, Map<String, Object> context) {
        this.reason = reason;
        this.summary = summary;
        this.context = context == null ? Collections.emptyMap() : Map.copyOf(context);
    }

    public String getReason() {
        return reason;
    }

    public String getSummary() {
        return summary;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    @Override
    public String toString() {
        return "HandoffAction{" +
                "reason=" + reason +
                ", summary=" + summary +
                ", context=" + context +
                "}";
    }
}
