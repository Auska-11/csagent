package com.smartcs.smart_cs_agent.workflow.api;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;

import java.util.Objects;

/**
 * Workflow 对“下一步做什么”的结构化决定。
 *
 * <p>旧 Skill 只返回 AgentAction。增加 decisionReason 后，Runtime 可以把决策原因写入 Trace，
 * 同时仍由 ActionHandler 负责真正执行 Action，实现“决策与执行分离”。</p>
 */
public final class WorkflowDecision {

    private final AgentAction action;
    private final String decisionReason;

    public WorkflowDecision(AgentAction action, String decisionReason) {
        this.action = Objects.requireNonNull(action, "Workflow decision action cannot be null");
        this.decisionReason = decisionReason == null ? "" : decisionReason;
    }

    public static WorkflowDecision next(AgentAction action, String reason) {
        return new WorkflowDecision(action, reason);
    }

    public AgentAction getAction() {
        return action;
    }

    public String getDecisionReason() {
        return decisionReason;
    }
}
