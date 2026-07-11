package com.smartcs.smart_cs_agent.mcp.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 远程 MCP Client 配置。 */
@Component
@ConfigurationProperties(prefix = "smartcs.mcp.client")
public class McpClientProperties {

    private String baseUrl = "http://localhost:8081/mcp";
    private String serverId = "remote-cs";
    private String serviceToken = "dev-mcp-service-token";
    private long timeoutMs = 10_000;
    private int maxRetries = 1;
    private int circuitFailureThreshold = 3;
    private long circuitOpenMs = 30_000;
    private long discoveryCacheTtlMs = 60_000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public long getCircuitOpenMs() {
        return circuitOpenMs;
    }

    public void setCircuitOpenMs(long circuitOpenMs) {
        this.circuitOpenMs = circuitOpenMs;
    }

    public long getDiscoveryCacheTtlMs() {
        return discoveryCacheTtlMs;
    }

    public void setDiscoveryCacheTtlMs(long discoveryCacheTtlMs) {
        this.discoveryCacheTtlMs = discoveryCacheTtlMs;
    }
}
