package com.smartcs.smart_cs_agent.workflow.returnapply;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 退货 V2 Workflow 功能开关，关闭后首次路由会继续使用 Legacy Workflow。 */
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
