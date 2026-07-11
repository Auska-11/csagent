package com.smartcs.smart_cs_agent.mcp.client;

/** 远程 MCP 连接健康快照。 */
public record McpTransportHealth(boolean available,
                                 int consecutiveFailures,
                                 long circuitOpenUntil,
                                 String lastError) {
}
