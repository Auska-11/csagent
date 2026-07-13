package com.smartcs.smart_cs_agent.workflow.returnapply;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 退货申请 Workflow 功能开关；关闭后不回退旧 Skill，而是由通用客服提示用户。 */
@Component
@ConfigurationProperties(prefix = "smartcs.workflow.return-apply-v2")
public class ReturnApplyWorkflowProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
