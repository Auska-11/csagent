package com.smartcs.smart_cs_agent.capability.core;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Capability Provider 运行模式：local、mcp-remote 或 hybrid。 */
@Component
@ConfigurationProperties(prefix = "smartcs.capability")
public class CapabilityProviderProperties {

    private String providerMode = "local";

    public String getProviderMode() {
        return providerMode;
    }

    public void setProviderMode(String providerMode) {
        this.providerMode = providerMode;
    }

    public boolean isHybrid() {
        return "hybrid".equalsIgnoreCase(providerMode);
    }
}
