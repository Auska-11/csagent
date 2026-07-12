package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.mcp.server.McpInvocationMetadata;
import com.smartcs.smart_cs_agent.mcp.server.McpServerAdapter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 便于浏览器调试的 REST 风格 MCP Server Adapter。
 *
 * <p>它只依赖 McpServerAdapter，不再认识 ToolManager/ToolRegistry。正式 JSON-RPC 与本接口因此
 * 共享同一个认证、CapabilityCatalog 和 ExecutionContext 入口。</p>
 */
@RestController
@RequestMapping("/api/mcp")
public class McpToolController {

    private final McpServerAdapter serverAdapter;

    public McpToolController(McpServerAdapter serverAdapter) {
        this.serverAdapter = serverAdapter;
    }

    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        List<CapabilityDefinition> tools = serverAdapter.listCapabilities();
        return Map.of(
                "protocol", "mcp-adapter-v2",
                "count", tools.size(),
                "tools", tools
        );
    }

    @PostMapping("/tools/call")
    public McpToolResponse callTool(
            @RequestBody(required = false) McpToolCallRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            return McpToolResponse.failure(null, "BAD_REQUEST", "Capability name cannot be empty");
        }

        CapabilityResult<?> result = serverAdapter.invoke(
                request.getName(),
                request.getArguments(),
                new McpInvocationMetadata(
                        request.getRequestId(),
                        "",
                        request.getUserId(),
                        request.getTenantId(),
                        request.getConversationId(),
                        request.getIdempotencyKey()
                ),
                principal
        );
        return McpToolResponse.from(result);
    }
}
