package com.smartcs.smart_cs_agent.monitor;

import java.util.List;

/**
 * Monitor 对外返回的聚合指标。
 *
 * 【第一版定位】
 * 这里不是复杂的 Prometheus 指标系统，而是一个面试项目里非常实用的“在线表现摘要”：
 * - Agent 一共处理了多少轮；
 * - 工具调用是否稳定；
 * - LLM 是否经常失败；
 * - 是否频繁转人工；
 * - 最近几轮对话的 Trace 摘要是什么。
 */
public class MonitorSummary {

    private final long totalTurns;
    private final long toolCallCount;
    private final long successfulToolCallCount;
    private final long failedToolCallCount;
    private final double toolSuccessRate;
    private final long llmCallCount;
    private final long llmFailureCount;
    private final double llmFailureRate;
    private final long handoffCount;
    private final long averageToolDurationMs;
    private final long averageLlmDurationMs;
    private final long lastUpdatedAt;
    private final List<MonitorTurnSnapshot> recentTurns;

    public MonitorSummary(long totalTurns,
                          long toolCallCount,
                          long successfulToolCallCount,
                          long failedToolCallCount,
                          double toolSuccessRate,
                          long llmCallCount,
                          long llmFailureCount,
                          double llmFailureRate,
                          long handoffCount,
                          long averageToolDurationMs,
                          long averageLlmDurationMs,
                          long lastUpdatedAt,
                          List<MonitorTurnSnapshot> recentTurns) {
        this.totalTurns = totalTurns;
        this.toolCallCount = toolCallCount;
        this.successfulToolCallCount = successfulToolCallCount;
        this.failedToolCallCount = failedToolCallCount;
        this.toolSuccessRate = toolSuccessRate;
        this.llmCallCount = llmCallCount;
        this.llmFailureCount = llmFailureCount;
        this.llmFailureRate = llmFailureRate;
        this.handoffCount = handoffCount;
        this.averageToolDurationMs = averageToolDurationMs;
        this.averageLlmDurationMs = averageLlmDurationMs;
        this.lastUpdatedAt = lastUpdatedAt;
        this.recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }

    public long getTotalTurns() {
        return totalTurns;
    }

    public long getToolCallCount() {
        return toolCallCount;
    }

    public long getSuccessfulToolCallCount() {
        return successfulToolCallCount;
    }

    public long getFailedToolCallCount() {
        return failedToolCallCount;
    }

    public double getToolSuccessRate() {
        return toolSuccessRate;
    }

    public long getLlmCallCount() {
        return llmCallCount;
    }

    public long getLlmFailureCount() {
        return llmFailureCount;
    }

    public double getLlmFailureRate() {
        return llmFailureRate;
    }

    public long getHandoffCount() {
        return handoffCount;
    }

    public long getAverageToolDurationMs() {
        return averageToolDurationMs;
    }

    public long getAverageLlmDurationMs() {
        return averageLlmDurationMs;
    }

    public long getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public List<MonitorTurnSnapshot> getRecentTurns() {
        return recentTurns;
    }
}
