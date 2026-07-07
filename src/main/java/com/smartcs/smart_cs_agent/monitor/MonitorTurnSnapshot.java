package com.smartcs.smart_cs_agent.monitor;

/**
 * 单轮对话的监控快照。
 *
 * 【为什么要有这个类？】
 * MonitorSummary 负责展示全局累计指标；
 * MonitorTurnSnapshot 负责保留最近几轮对话的摘要，方便前端和面试演示时看到“刚才这一轮发生了什么”。
 */
public class MonitorTurnSnapshot {

    /** 会话 ID：用于定位是哪一个用户会话产生了这条指标 */
    private final String conversationId;

    /** 用户 ID：用于区分不同用户的调用表现 */
    private final String userId;

    /** 本轮响应时间戳，直接复用 ChatResponse 的 timestamp */
    private final long timestamp;

    /** 本轮完成的工具调用次数 */
    private final long toolCalls;

    /** 本轮失败的工具调用次数 */
    private final long failedToolCalls;

    /** 本轮 LLM 调用次数 */
    private final long llmCalls;

    /** 本轮 LLM 失败次数 */
    private final long llmFailures;

    /** 本轮是否触发过人工转接，第一版用次数表示，后续可扩展为转接原因分布 */
    private final long handoffCount;

    /** 本轮 Trace 中记录到的工具和 LLM 耗时总和 */
    private final long totalDurationMs;

    public MonitorTurnSnapshot(String conversationId,
                               String userId,
                               long timestamp,
                               long toolCalls,
                               long failedToolCalls,
                               long llmCalls,
                               long llmFailures,
                               long handoffCount,
                               long totalDurationMs) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.timestamp = timestamp;
        this.toolCalls = toolCalls;
        this.failedToolCalls = failedToolCalls;
        this.llmCalls = llmCalls;
        this.llmFailures = llmFailures;
        this.handoffCount = handoffCount;
        this.totalDurationMs = totalDurationMs;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getToolCalls() {
        return toolCalls;
    }

    public long getFailedToolCalls() {
        return failedToolCalls;
    }

    public long getLlmCalls() {
        return llmCalls;
    }

    public long getLlmFailures() {
        return llmFailures;
    }

    public long getHandoffCount() {
        return handoffCount;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }
}
