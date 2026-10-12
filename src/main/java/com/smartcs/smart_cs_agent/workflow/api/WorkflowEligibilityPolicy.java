package com.smartcs.smart_cs_agent.workflow.api;

/**
 * 在评分、恢复活跃任务和兜底之前决定 Workflow 是否适用于当前请求。
 * 渠道或租户限制由独立实现提供；所有策略都允许时才可执行。
 */
@FunctionalInterface
public interface WorkflowEligibilityPolicy {
    boolean isEligible(AgentWorkflow workflow, RoutingContext context);
}
