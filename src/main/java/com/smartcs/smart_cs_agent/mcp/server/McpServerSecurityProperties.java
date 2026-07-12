package com.smartcs.smart_cs_agent.mcp.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** MCP Server 的公开标识；服务认证已经统一由 HTTP Bearer JWT 负责。 */
@Component
@ConfigurationProperties(prefix = "smartcs.mcp.server")
public class McpServerSecurityProperties {

    private String serverId = "smart-cs-local";

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }
}
