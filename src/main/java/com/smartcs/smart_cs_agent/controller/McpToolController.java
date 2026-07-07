package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.tools.ToolCall;
import com.smartcs.smart_cs_agent.tools.ToolDefinition;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP Adapter 接口。
 *
 * 【当前定位】
 * 这不是完整独立 MCP Server，而是第一版 MCP 边界适配层。
 * 它把项目内部已有的 ToolManager / ToolRegistry 暴露成更接近 MCP tools/list、tools/call 的接口。
 *
 * 【为什么先做 Adapter？】
 * - 当前项目还在一个 Spring Boot 应用里，直接拆独立进程会增加很多部署成本。
 * - Adapter 能先把协议边界固定下来：外部只关心工具列表、schema 和调用结果。
 * - 后续如果迁移成真正 MCP Server，可以优先复用这里的 DTO 和映射逻辑。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpToolController {

    private final ToolManager toolManager;

    public McpToolController(ToolManager toolManager) {
        this.toolManager = toolManager;
    }

    /**
     * MCP-style tools/list。
     *
     * 返回当前系统可调用工具的名称、说明、inputSchema 和 outputSchema。
     */
    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        List<ToolDefinition> tools = toolManager.listTools();
        return Map.of(
                "protocol", "mcp-adapter-v1",
                "count", tools.size(),
                "tools", tools
        );
    }

    /**
     * MCP-style tools/call。
     *
     * 注意：
     * - 这里不直接抛出业务异常，而是统一返回 McpToolResponse。
     * - 工具不存在、参数缺失等情况由 ToolRegistry 转成 ToolResult failure。
     */
    @PostMapping("/tools/call")
    public McpToolResponse callTool(@RequestBody(required = false) McpToolCallRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            return McpToolResponse.failure(null, "BAD_REQUEST", "Tool name cannot be empty");
        }

        ToolResult result = toolManager.execute(new ToolCall(request.getName(), request.getArguments()));
        return McpToolResponse.from(result);
    }
}
