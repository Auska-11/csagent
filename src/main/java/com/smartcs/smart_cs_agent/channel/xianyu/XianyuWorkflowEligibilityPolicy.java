package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowEligibilityPolicy;
import org.springframework.stereotype.Component;

/** 真实闲鱼会话排除模拟商城流程，避免买家收到索要演示订单号的追问。 */
@Component
public class XianyuWorkflowEligibilityPolicy implements WorkflowEligibilityPolicy {
    private final XianyuItemFactService facts;
    private final XianyuScenarioProperties properties;

    public XianyuWorkflowEligibilityPolicy(XianyuItemFactService facts, XianyuScenarioProperties properties) {
        this.facts = facts;
        this.properties = properties;
    }

    @Override
    public boolean isEligible(AgentWorkflow workflow, RoutingContext context) {
        return properties.isWorkflowAllowed(workflow.descriptor().getId())
                || !facts.isXianyuTenant(context.getRequest().getTenantId());
    }
}
