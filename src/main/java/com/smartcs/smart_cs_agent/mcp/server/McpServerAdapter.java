package com.smartcs.smart_cs_agent.mcp.server;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.security.DemoAuthenticationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 协议到 CapabilityGateway 的服务端适配器。
 *
 * <p>Controller 只解析 JSON-RPC/REST；认证、ExecutionContext 和目录查询集中在这里。
 * 本类不依赖 ToolManager、ToolRegistry 或任何领域 Tool。</p>
 */
@Component
public class McpServerAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpServerAdapter.class);
    private static final String MCP_SERVER_PROVIDER = "mcp-server-adapter";

    private final CapabilityGateway capabilityGateway;
    private final DemoAuthenticationService authenticationService;
    private final McpServerSecurityProperties properties;

    public McpServerAdapter(CapabilityGateway capabilityGateway,
                            DemoAuthenticationService authenticationService,
                            McpServerSecurityProperties properties) {
        this.capabilityGateway = capabilityGateway;
        this.authenticationService = authenticationService;
        this.properties = properties;
    }

    public List<CapabilityDefinition> listCapabilities() {
        return List.copyOf(capabilityGateway.catalog().list());
    }

    public CapabilityResult<?> invoke(String capabilityId,
                                      Map<String, Object> arguments,
                                      McpInvocationMetadata metadata) {
        long startedAtNanos = System.nanoTime();
        McpInvocationMetadata safeMetadata = metadata == null
                ? McpInvocationMetadata.anonymous("")
                : metadata;

        AuthenticatedPrincipal principal;
        try {
            principal = authenticate(safeMetadata);
        } catch (IllegalArgumentException e) {
            return CapabilityResult.businessRejected(
                    capabilityId,
                    MCP_SERVER_PROVIDER,
                    "MCP_AUTHENTICATION_FAILED",
                    "MCP authentication failed",
                    null
            );
        }

        // MCP 是外部执行边界：即使某项 Capability 本身是公开读，也要求调用方先通过 MCP 认证。
        if (!principal.isAuthenticated()) {
            return CapabilityResult.businessRejected(
                    capabilityId,
                    MCP_SERVER_PROVIDER,
                    "MCP_AUTHENTICATION_REQUIRED",
                    "MCP tools/call requires authentication",
                    null
            );
        }

        ExecutionContext context = new ExecutionContext(
                safeMetadata.getRequestId(),
                principal,
                defaultText(safeMetadata.getTenantId(), "default"),
                safeMetadata.getConversationId(),
                safeMetadata.getIdempotencyKey(),
                Instant.now().plusSeconds(30)
        );
        CapabilityResult<?> result = capabilityGateway.invoke(
                new CapabilityCall(capabilityId, arguments),
                context
        );

        // 只记录协议元数据和结果，不记录 arguments，避免订单号/用户数据进入普通日志。
        log.info(
                "MCP call serverId={}, requestId={}, capability={}, provider={}, outcome={}, replayed={}, durationMs={}",
                properties.getServerId(),
                safeMetadata.getRequestId(),
                capabilityId,
                result.getProviderId(),
                result.getOutcome(),
                result.isReplayed(),
                Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000)
        );
        return result;
    }

    private AuthenticatedPrincipal authenticate(McpInvocationMetadata metadata) {
        if (!properties.getServiceToken().isBlank()
                && properties.getServiceToken().equals(metadata.getAuthToken())) {
            if (metadata.getUserId().isBlank()) {
                throw new IllegalArgumentException("Trusted MCP call must include userId");
            }
            return AuthenticatedPrincipal.authenticated(
                    metadata.getUserId(),
                    Set.of("CUSTOMER", "MCP_TRUSTED_CALLER")
            );
        }
        return authenticationService.authenticate(metadata.getUserId(), metadata.getAuthToken());
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
