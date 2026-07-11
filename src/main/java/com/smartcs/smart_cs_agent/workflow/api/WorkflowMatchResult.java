package com.smartcs.smart_cs_agent.workflow.api;

/**
 * Workflow 对一次路由请求的匹配结果。
 *
 * <p>score 范围为 0 到 1；reason 用于 Trace 和排查“为什么选中这个 Workflow”。
 * Router 后续会同时比较 score、priority 和活跃任务状态，而不是只看关键词。</p>
 */
public final class WorkflowMatchResult {

    private final double score;
    private final String reason;

    public WorkflowMatchResult(double score, String reason) {
        if (Double.isNaN(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Workflow match score must be between 0 and 1");
        }
        this.score = score;
        this.reason = reason == null ? "" : reason;
    }

    public static WorkflowMatchResult noMatch(String reason) {
        return new WorkflowMatchResult(0.0, reason);
    }

    public double getScore() {
        return score;
    }

    public String getReason() {
        return reason;
    }
}
