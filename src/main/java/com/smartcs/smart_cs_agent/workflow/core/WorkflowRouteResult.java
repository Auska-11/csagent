package com.smartcs.smart_cs_agent.workflow.core;

import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;

import java.util.List;
import java.util.Objects;

/**
 * 一次 Workflow 路由的完整结果。
 *
 * <p>selectedWorkflow 给 Runtime 执行；其余字段只用于 Trace、测试和调试。
 * 这样 Router 决策仍可解释，但 Runtime 不需要重新计算匹配分数。</p>
 */
public final class WorkflowRouteResult {

    private final AgentWorkflow selectedWorkflow;
    private final boolean resumed;
    private final double selectedScore;
    private final String reason;
    private final List<WorkflowCandidate> candidates;

    public WorkflowRouteResult(AgentWorkflow selectedWorkflow,
                               boolean resumed,
                               double selectedScore,
                               String reason,
                               List<WorkflowCandidate> candidates) {
        this.selectedWorkflow = Objects.requireNonNull(selectedWorkflow, "selectedWorkflow cannot be null");
        this.resumed = resumed;
        this.selectedScore = selectedScore;
        this.reason = reason == null ? "" : reason;
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public AgentWorkflow getSelectedWorkflow() {
        return selectedWorkflow;
    }

    public boolean isResumed() {
        return resumed;
    }

    public double getSelectedScore() {
        return selectedScore;
    }

    public String getReason() {
        return reason;
    }

    public List<WorkflowCandidate> getCandidates() {
        return candidates;
    }
}
