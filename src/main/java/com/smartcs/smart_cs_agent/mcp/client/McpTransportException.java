package com.smartcs.smart_cs_agent.mcp.client;

/** 远程 MCP 协议、连接或响应解析失败。 */
public class McpTransportException extends RuntimeException {

    public McpTransportException(String message) {
        super(message);
    }

    public McpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
