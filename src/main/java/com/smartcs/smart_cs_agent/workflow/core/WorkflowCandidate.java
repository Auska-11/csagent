package com.smartcs.smart_cs_agent.workflow.core;

/**
 * Router 对一个 Workflow 的可观察评分。
 *
 * <p>把所有候选分数保留下来，Trace 才能回答“为什么选 A 而没选 B”，
 * 而不是只记录最后一个名字。</p>
 */
public final class WorkflowCandidate {

    private final String workflowId;
    private final String version;
    private final double score;
    private final int priority;
    private final String reason;

    public WorkflowCandidate(String workflowId,
                             String version,
                             double score,
                             int priority,
                             String reason) {
        this.workflowId = workflowId;
        this.version = version;
        this.score = score;
        this.priority = priority;
        this.reason = reason == null ? "" : reason;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getVersion() {
        return version;
    }

    public double getScore() {
        return score;
    }

    public int getPriority() {
        return priority;
    }

    public String getReason() {
        return reason;
    }
}
