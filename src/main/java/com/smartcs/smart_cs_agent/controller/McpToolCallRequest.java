package com.smartcs.smart_cs_agent.controller;

import java.util.Collections;
import java.util.Map;

/**
 * MCP Adapter 工具调用请求。
 *
 * 【为什么不直接复用 ToolCall？】
 * ToolCall 是项目内部工具协议对象，主要给 Runtime 和 ToolManager 使用。
 * McpToolCallRequest 是 HTTP 接口入参，属于外部协议边界。
 *
 * 两者字段目前很像，但职责不同：
 * - McpToolCallRequest：负责接收前端或外部调用方传来的 JSON。
 * - ToolCall：负责进入内部工具注册表执行。
 *
 * 这样分开后，后续真正迁移成独立 MCP Server 时，外部协议可以继续演进，
 * 不会把内部 ToolCall 结构绑死。
 */
public class McpToolCallRequest {

    /** 工具名称，例如 order_lookup */
    private String name;

    /** 工具参数，例如 {"order_id": "ORD-001"} */
    private Map<String, Object> arguments = Collections.emptyMap();

    public McpToolCallRequest() {
    }

    public McpToolCallRequest(String name, Map<String, Object> arguments) {
        this.name = name;
        this.arguments = arguments == null ? Collections.emptyMap() : arguments;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getArguments() {
        return arguments == null ? Collections.emptyMap() : arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments == null ? Collections.emptyMap() : arguments;
    }
}
