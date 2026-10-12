package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.mcp.server.McpInvocationMetadata;
import com.smartcs.smart_cs_agent.mcp.server.McpServerAdapter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-RPC MCP Server 入口；协议解析之后全部委托 McpServerAdapter。 */
@RestController
public class McpJsonRpcController {

    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_REQUEST = -32600;
    private static final int INVALID_PARAMS = -32602;

    private final McpServerAdapter serverAdapter;

    public McpJsonRpcController(McpServerAdapter serverAdapter) {
        this.serverAdapter = serverAdapter;
    }

    @PostMapping("/mcp")
    public ResponseEntity<McpJsonRpcResponse> handleHttp(
            @RequestBody(required = false) McpJsonRpcRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        if (request != null && "2.0".equals(request.getJsonrpc())
                && request.getMethod() != null && !request.getMethod().isBlank() && !request.hasId()) {
            // 已初始化、取消等通知不产生 JSON-RPC 响应；未知通知也忽略。
            // tools/call 必须通过带 id 的请求执行，不能用通知静默触发业务能力。
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(handle(request, principal));
    }

    public McpJsonRpcResponse handle(McpJsonRpcRequest request, AuthenticatedPrincipal principal) {
        if (request == null || !"2.0".equals(request.getJsonrpc())) {
            return McpJsonRpcResponse.error(null, INVALID_REQUEST, "JSON-RPC version must be 2.0");
        }
        if (request.getMethod() == null || request.getMethod().isBlank()) {
            return McpJsonRpcResponse.error(null, INVALID_PARAMS, "MCP method cannot be empty");
        }

        return switch (request.getMethod()) {
            case "initialize" -> McpJsonRpcResponse.success(request.getId(), initializeResult());
            case "ping" -> McpJsonRpcResponse.success(request.getId(), Map.of());
            case "tools/list" -> McpJsonRpcResponse.success(request.getId(), toolsListResult());
            case "tools/call" -> McpJsonRpcResponse.success(
                    request.getId(),
                    toolsCallResult(request.getId(), request.getParams(), principal)
            );
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
                        "version", "0.2.0"
                ),
                "capabilities", Map.of("tools", Map.of("listChanged", false))
        );
    }

    private Map<String, Object> toolsListResult() {
        List<Map<String, Object>> tools = serverAdapter.listCapabilities()
                .stream()
                .map(this::toMcpTool)
                .toList();
        return Map.of("tools", tools);
    }

    private Map<String, Object> toMcpTool(CapabilityDefinition definition) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", definition.getId());
        tool.put("description", definition.getDescription());
        tool.put("inputSchema", definition.getInputSchema());
        tool.put("outputSchema", definition.getOutputSchema());
        tool.put("_meta", Map.of(
                "version", definition.getVersion(),
                "requiresIdentity", definition.isRequiresIdentity(),
                "requiresAuthentication", definition.isRequiresAuthentication(),
                "sideEffect", definition.isSideEffect(),
                "idempotent", definition.isIdempotent(),
                "defaultTimeoutMs", definition.getDefaultTimeoutMs(),
                "riskLevel", definition.getRiskLevel().name()
        ));
        return tool;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCallResult(
            Object requestId,
            Map<String, Object> params,
            AuthenticatedPrincipal principal) {
        if (params == null || params.isEmpty()) {
            return errorToolResult("BAD_REQUEST", "Tool call params cannot be empty", null);
        }

        String name = text(params.get("name"));
        if (name.isBlank()) {
            return errorToolResult("BAD_REQUEST", "Capability name cannot be empty", null);
        }

        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> rawArguments
                ? (Map<String, Object>) rawArguments
                : Map.of();
        Map<String, Object> meta = params.get("_meta") instanceof Map<?, ?> rawMeta
                ? (Map<String, Object>) rawMeta
                : params;

        CapabilityResult<?> result = serverAdapter.invoke(
                name,
                arguments,
                new McpInvocationMetadata(
                        defaultText(text(meta.get("requestId")), String.valueOf(requestId)),
                        "",
                        text(meta.get("userId")),
                        text(meta.get("tenantId")),
                        text(meta.get("conversationId")),
                        text(meta.get("idempotencyKey"))
                ),
                principal
        );
        if (!result.isSuccess()) {
            return errorToolResult(result.getErrorCode(), result.getMessage(), result);
        }

        Map<String, Object> response = baseToolResult(result);
        response.put("isError", false);
        return response;
    }

    private Map<String, Object> errorToolResult(String errorCode,
                                                String message,
                                                CapabilityResult<?> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(Map.of(
                "type", "text",
                "text", message == null ? "" : message
        )));
        response.put("structuredContent", result == null ? null : result.getData());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("errorCode", errorCode == null ? "CAPABILITY_ERROR" : errorCode);
        if (result != null) {
            meta.put("capabilityId", result.getCapabilityId());
            meta.put("providerId", result.getProviderId());
            meta.put("outcome", result.getOutcome().name());
            meta.put("replayed", result.isReplayed());
            if (!result.getMetadata().isEmpty()) {
                meta.put("capabilityMetadata", result.getMetadata());
            }
        }
        response.put("_meta", meta);
        response.put("isError", true);
        return response;
    }

    private Map<String, Object> baseToolResult(CapabilityResult<?> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of(Map.of("type", "text", "text", result.getMessage())));
        response.put("structuredContent", result.getData());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("capabilityId", result.getCapabilityId());
        meta.put("providerId", result.getProviderId());
        meta.put("outcome", result.getOutcome().name());
        meta.put("replayed", result.isReplayed());
        if (!result.getMetadata().isEmpty()) {
            meta.put("capabilityMetadata", result.getMetadata());
        }
        response.put("_meta", meta);
        return response;
    }

    private String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
