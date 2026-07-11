package com.smartcs.smart_cs_agent.mcp.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** MCP Server 的轻量服务间认证配置。 */
@Component
@ConfigurationProperties(prefix = "smartcs.mcp.server")
public class McpServerSecurityProperties {

    /** 演示默认值只用于本地；部署时必须通过环境变量替换。 */
    private String serviceToken = "dev-mcp-service-token";

    private String serverId = "smart-cs-local";

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }
}
