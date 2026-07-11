package com.smartcs.smart_cs_agent.workflow.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Workflow Router 的可调参数。 */
@Component
@ConfigurationProperties(prefix = "smartcs.workflow.router")
public class WorkflowRouterProperties {

    /** 首次匹配低于该分数时使用 fallback Workflow。 */
    private double minimumScore = 0.25;

    /** 无可靠候选时的通用客服 Workflow。 */
    private String fallbackWorkflowId = "customer_service";

    public double getMinimumScore() {
        return minimumScore;
    }

    public void setMinimumScore(double minimumScore) {
        if (minimumScore < 0.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException("minimumScore must be between 0 and 1");
        }
        this.minimumScore = minimumScore;
    }

    public String getFallbackWorkflowId() {
        return fallbackWorkflowId;
    }

    public void setFallbackWorkflowId(String fallbackWorkflowId) {
        this.fallbackWorkflowId = fallbackWorkflowId;
    }
}
