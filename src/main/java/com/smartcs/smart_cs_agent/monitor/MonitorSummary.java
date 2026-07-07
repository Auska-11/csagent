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
    private final long feedbackCount;
    private final long helpfulFeedbackCount;
    private final long unhelpfulFeedbackCount;
    private final double helpfulFeedbackRate;
    private final long averageToolDurationMs;
    private final long averageLlmDurationMs;
    private final long lastUpdatedAt;
    private final List<MonitorTurnSnapshot> recentTurns;
    private final List<ToolMonitorSnapshot> toolStats;
    private final List<FeedbackRecord> recentFeedbacks;
    private final long evalCandidateCount;
    private final List<FeedbackEvalCandidate> recentEvalCandidates;

    public MonitorSummary(long totalTurns,
                          long toolCallCount,
                          long successfulToolCallCount,
                          long failedToolCallCount,
                          double toolSuccessRate,
                          long llmCallCount,
                          long llmFailureCount,
                          double llmFailureRate,
                          long handoffCount,
                          long feedbackCount,
                          long helpfulFeedbackCount,
                          long unhelpfulFeedbackCount,
                          double helpfulFeedbackRate,
                          long averageToolDurationMs,
                          long averageLlmDurationMs,
                          long lastUpdatedAt,
                          List<MonitorTurnSnapshot> recentTurns,
                          List<ToolMonitorSnapshot> toolStats,
                          List<FeedbackRecord> recentFeedbacks,
                          long evalCandidateCount,
                          List<FeedbackEvalCandidate> recentEvalCandidates) {
        this.totalTurns = totalTurns;
        this.toolCallCount = toolCallCount;
        this.successfulToolCallCount = successfulToolCallCount;
        this.failedToolCallCount = failedToolCallCount;
        this.toolSuccessRate = toolSuccessRate;
        this.llmCallCount = llmCallCount;
        this.llmFailureCount = llmFailureCount;
        this.llmFailureRate = llmFailureRate;
        this.handoffCount = handoffCount;
        this.feedbackCount = feedbackCount;
        this.helpfulFeedbackCount = helpfulFeedbackCount;
        this.unhelpfulFeedbackCount = unhelpfulFeedbackCount;
        this.helpfulFeedbackRate = helpfulFeedbackRate;
        this.averageToolDurationMs = averageToolDurationMs;
        this.averageLlmDurationMs = averageLlmDurationMs;
        this.lastUpdatedAt = lastUpdatedAt;
        this.recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
        this.toolStats = toolStats == null ? List.of() : List.copyOf(toolStats);
        this.recentFeedbacks = recentFeedbacks == null ? List.of() : List.copyOf(recentFeedbacks);
        this.evalCandidateCount = evalCandidateCount;
        this.recentEvalCandidates = recentEvalCandidates == null ? List.of() : List.copyOf(recentEvalCandidates);
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

    public long getFeedbackCount() {
        return feedbackCount;
    }

    public long getHelpfulFeedbackCount() {
        return helpfulFeedbackCount;
    }

    public long getUnhelpfulFeedbackCount() {
        return unhelpfulFeedbackCount;
    }

    public double getHelpfulFeedbackRate() {
        return helpfulFeedbackRate;
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

    public List<ToolMonitorSnapshot> getToolStats() {
        return toolStats;
    }

    public List<FeedbackRecord> getRecentFeedbacks() {
        return recentFeedbacks;
    }

    public long getEvalCandidateCount() {
        return evalCandidateCount;
    }

    public List<FeedbackEvalCandidate> getRecentEvalCandidates() {
        return recentEvalCandidates;
    }
}
