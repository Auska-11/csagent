package com.smartcs.smart_cs_agent.mcp.client;

/** MCP initialize 返回的远程服务信息。 */
public record McpRemoteServerInfo(String protocolVersion, String name, String version) {
}
