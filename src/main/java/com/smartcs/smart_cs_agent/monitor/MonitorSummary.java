package com.smartcs.smart_cs_agent.monitor;

import java.util.List;
import java.util.Map;

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

    /** 当前候选池中仍然等待人工审核的样本数量 */
    private final long pendingEvalCandidateCount;

    /** 当前候选池中已经审核通过、可以导出为 Eval 草稿的样本数量 */
    private final long approvedEvalCandidateCount;

    /** 当前候选池中已经被人工拒绝、不进入评测草稿的样本数量 */
    private final long rejectedEvalCandidateCount;

    /** 按问题类型统计的候选样本数量，例如 MISSING_KEY_INFO -> 3 */
    private final Map<String, Long> evalCandidateIssueCounts;

    private final List<FeedbackEvalCandidate> recentEvalCandidates;

    /** 由 Monitor 指标自动生成的风险告警，帮助开发者优先处理最值得关注的问题 */
    private final List<MonitorAlert> alerts;

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
                          long pendingEvalCandidateCount,
                          long approvedEvalCandidateCount,
                          long rejectedEvalCandidateCount,
                          Map<String, Long> evalCandidateIssueCounts,
                          List<FeedbackEvalCandidate> recentEvalCandidates,
                          List<MonitorAlert> alerts) {
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
        this.pendingEvalCandidateCount = pendingEvalCandidateCount;
        this.approvedEvalCandidateCount = approvedEvalCandidateCount;
        this.rejectedEvalCandidateCount = rejectedEvalCandidateCount;
        this.evalCandidateIssueCounts = evalCandidateIssueCounts == null ? Map.of() : Map.copyOf(evalCandidateIssueCounts);
        this.recentEvalCandidates = recentEvalCandidates == null ? List.of() : List.copyOf(recentEvalCandidates);
        this.alerts = alerts == null ? List.of() : List.copyOf(alerts);
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

    public long getPendingEvalCandidateCount() {
        return pendingEvalCandidateCount;
    }

    public long getApprovedEvalCandidateCount() {
        return approvedEvalCandidateCount;
    }

    public long getRejectedEvalCandidateCount() {
        return rejectedEvalCandidateCount;
    }

    public Map<String, Long> getEvalCandidateIssueCounts() {
        return evalCandidateIssueCounts;
    }

    public List<FeedbackEvalCandidate> getRecentEvalCandidates() {
        return recentEvalCandidates;
    }

    public List<MonitorAlert> getAlerts() {
        return alerts;
    }
}
