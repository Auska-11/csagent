package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.tools.ToolCall;
import com.smartcs.smart_cs_agent.tools.ToolDefinition;
import com.smartcs.smart_cs_agent.tools.ToolManager;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立 MCP Server 第一版入口。
 *
 * 【和 /api/mcp 的区别】
 * - /api/mcp/tools 是 REST Adapter，方便前端和普通 HTTP 调试。
 * - /mcp 是 JSON-RPC 风格入口，更接近 MCP Client 期望的协议形态。
 *
 * 当前它仍然运行在同一个 Spring Boot 进程里，但协议边界已经独立：
 * 外部 MCP Client 只需要关心 initialize、tools/list、tools/call，
 * 不需要知道内部 ToolRegistry、ToolManager、OrderTools 怎么实现。
 */
@RestController
public class McpJsonRpcController {

    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    private final ToolManager toolManager;

    public McpJsonRpcController(ToolManager toolManager) {
        this.toolManager = toolManager;
    }

    @PostMapping("/mcp")
    public McpJsonRpcResponse handle(@RequestBody(required = false) McpJsonRpcRequest request) {
        if (request == null || request.getMethod() == null || request.getMethod().isBlank()) {
            return McpJsonRpcResponse.error(null, INVALID_PARAMS, "MCP method cannot be empty");
        }

        return switch (request.getMethod()) {
            case "initialize" -> McpJsonRpcResponse.success(request.getId(), initializeResult());
            case "tools/list" -> McpJsonRpcResponse.success(request.getId(), toolsListResult());
            case "tools/call" -> McpJsonRpcResponse.success(request.getId(), toolsCallResult(request.getParams()));
            default -> McpJsonRpcResponse.error(
                    request.getId(),
                    METHOD_NOT_FOUND,
                    "Unsupported MCP method: " + request.getMethod()
            );
        };
    }

    private Map<String, Object> initializeResult() {
        return Map.of(
                "protocolVersion", "2024-11-05",
                "serverInfo", Map.of(
                        "name", "smart-cs-agent-mcp-server",
                        "version", "0.1.0"
                ),
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", false)
                )
        );
    }

    private Map<String, Object> toolsListResult() {
        List<Map<String, Object>> tools = toolManager.listTools()
                .stream()
                .map(this::toMcpTool)
                .toList();
        return Map.of("tools", tools);
    }

    private Map<String, Object> toMcpTool(ToolDefinition definition) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", definition.getName());
        tool.put("description", definition.getDescription());
        tool.put("inputSchema", definition.getInputSchema());
        tool.put("outputSchema", definition.getOutputSchema());
        return tool;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCallResult(Map<String, Object> params) {
        if (params == null) {
            return errorToolResult("BAD_REQUEST", "Tool call params cannot be empty");
        }

        Object nameValue = params.get("name");
        if (nameValue == null || nameValue.toString().isBlank()) {
            return errorToolResult("BAD_REQUEST", "Tool name cannot be empty");
        }

        Object argumentsValue = params.get("arguments");
        Map<String, Object> arguments = argumentsValue instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        ToolResult result = toolManager.execute(new ToolCall(nameValue.toString(), arguments));
        if (!result.isSuccess()) {
            return errorToolResult(result.getErrorCode(), result.getMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(Map.of(
                "type", "text",
                "text", result.getMessage()
        )));
        response.put("structuredContent", result.getData());
        response.put("isError", false);
        return response;
    }

    private Map<String, Object> errorToolResult(String errorCode, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(Map.of(
                "type", "text",
                "text", message == null ? "" : message
        )));
        response.put("structuredContent", Map.of(
                "errorCode", errorCode == null ? "TOOL_ERROR" : errorCode
        ));
        response.put("isError", true);
        return response;
    }
}
