package com.smartcs.smart_cs_agent.capability.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 选择 Capability 来自本地 Provider、远程 MCP，或两者并存。 */
@Component
@ConfigurationProperties(prefix = "smartcs.capability")
public class CapabilityProviderModeProperties {

    private String providerMode = "local";

    public String getProviderMode() {
        return providerMode;
    }

    public void setProviderMode(String providerMode) {
        this.providerMode = providerMode;
    }

    public boolean isRemoteOnly() {
        return "mcp-remote".equalsIgnoreCase(providerMode);
    }

    public boolean isHybrid() {
        return "hybrid".equalsIgnoreCase(providerMode);
    }
}
