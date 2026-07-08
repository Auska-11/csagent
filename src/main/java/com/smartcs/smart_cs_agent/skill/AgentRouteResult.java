package com.smartcs.smart_cs_agent.skill;

/**
 * Multi-Agent 路由结果。
 *
 * 【为什么单独建模？】
 * 面试里讲 Multi-Agent 时，重点不是“创建很多类”，而是：
 * - 用户请求应该交给哪个专家域；
 * - 为什么这么路由；
 * - 路由置信度是多少；
 * - Trace 能否看到路由决策。
 *
 * 第一版先让路由结果结构化，后续再把不同 routeKey 拆成真正独立 Agent。
 */
public class AgentRouteResult {

    private final String routeKey;
    private final String agentName;
    private final String reason;
    private final double confidence;

    public AgentRouteResult(String routeKey, String agentName, String reason, double confidence) {
        this.routeKey = routeKey;
        this.agentName = agentName;
        this.reason = reason;
        this.confidence = confidence;
    }

    public String getRouteKey() {
        return routeKey;
    }

    public String getAgentName() {
        return agentName;
    }

    public String getReason() {
        return reason;
    }

    public double getConfidence() {
        return confidence;
    }
}
