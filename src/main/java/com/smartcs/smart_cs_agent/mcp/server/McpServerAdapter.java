package com.smartcs.smart_cs_agent.mcp.server;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.security.SecurityRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    private final McpServerSecurityProperties properties;

    public McpServerAdapter(CapabilityGateway capabilityGateway,
                            McpServerSecurityProperties properties) {
        this.capabilityGateway = capabilityGateway;
        this.properties = properties;
    }

    public List<CapabilityDefinition> listCapabilities() {
        return List.copyOf(capabilityGateway.catalog().list());
    }

    public CapabilityResult<?> invoke(String capabilityId,
                                      Map<String, Object> arguments,
                                      McpInvocationMetadata metadata,
                                      AuthenticatedPrincipal callerPrincipal) {
        long startedAtNanos = System.nanoTime();
        McpInvocationMetadata safeMetadata = metadata == null
                ? McpInvocationMetadata.anonymous("")
                : metadata;

        CapabilityResult<?> result;

        // MCP 是外部执行边界：即使某项 Capability 本身是公开读，也要求调用方先通过 MCP 认证。
        // 注意这里不能直接 return。所有拒绝和成功结果最终都要经过同一个 auditResult，
        // 否则越早失败的安全请求反而越没有审计记录。
        if (callerPrincipal == null || !callerPrincipal.isAuthenticated()) {
            result = CapabilityResult.businessRejected(
                    capabilityId,
                    MCP_SERVER_PROVIDER,
                    "MCP_AUTHENTICATION_REQUIRED",
                    "MCP tools/call requires authentication",
                    null
            );
        } else {
            var definition = capabilityGateway.catalog().get(capabilityId);
            if (callerPrincipal.hasRole(SecurityRoles.MCP_CLIENT)
                    && definition != null
                    && (definition.isRequiresIdentity() || definition.isRequiresAuthentication())) {
                // 服务 JWT 只能证明“哪个服务在调用”，不能把 _meta.userId 升级成任意客户。
                // 在签名委托 token / token-exchange 完成前，身份型 Capability 必须失败关闭。
                result = CapabilityResult.businessRejected(
                        capabilityId,
                        MCP_SERVER_PROVIDER,
                        "MCP_DELEGATION_REQUIRED",
                        "MCP identity capability requires a signed user delegation",
                        null
                );
            } else if ((!safeMetadata.getUserId().isBlank()
                    && !safeMetadata.getUserId().equals(callerPrincipal.getUserId()))
                    || (!safeMetadata.getTenantId().isBlank()
                    && !safeMetadata.getTenantId().equals(callerPrincipal.getTenantId()))) {
                result = CapabilityResult.businessRejected(
                        capabilityId,
                        MCP_SERVER_PROVIDER,
                        "MCP_IDENTITY_METADATA_REJECTED",
                        "MCP identity metadata cannot override the authenticated caller",
                        null
                );
            } else {
                ExecutionContext context = new ExecutionContext(
                        safeMetadata.getRequestId(),
                        callerPrincipal,
                        callerPrincipal.getTenantId(),
                        safeMetadata.getConversationId(),
                        safeMetadata.getIdempotencyKey(),
                        Instant.now().plusSeconds(30)
                );
                result = capabilityGateway.invoke(
                        new CapabilityCall(capabilityId, arguments),
                        context
                );
            }
        }

        return auditResult(
                capabilityId,
                safeMetadata,
                callerPrincipal,
                result,
                startedAtNanos
        );
    }

    /**
     * MCP 调用的唯一普通日志出口。
     *
     * <p>arguments、token、用户/租户、会话和幂等键都不属于普通运维日志需要的数据，因此本方法
     * 根本不接收它们。requestId 使用短哈希保留关联能力，其他标识统一限长和清理，避免换行伪造
     * 日志或把外部敏感文本写入日志。</p>
     */
    private CapabilityResult<?> auditResult(String capabilityId,
                                            McpInvocationMetadata metadata,
                                            AuthenticatedPrincipal callerPrincipal,
                                            CapabilityResult<?> result,
                                            long startedAtNanos) {
        log.info(
                "MCP audit serverId={}, requestRef={}, capability={}, callerType={}, provider={}, "
                        + "outcome={}, errorCode={}, replayed={}, durationMs={}",
                McpAuditLogSanitizer.safeIdentifier(properties.getServerId(), "unknown-server"),
                McpAuditLogSanitizer.requestReference(metadata.getRequestId()),
                McpAuditLogSanitizer.safeIdentifier(capabilityId, "unknown-capability"),
                callerType(callerPrincipal),
                McpAuditLogSanitizer.safeIdentifier(result.getProviderId(), "unknown-provider"),
                result.getOutcome(),
                McpAuditLogSanitizer.safeIdentifier(result.getErrorCode(), "NONE"),
                result.isReplayed(),
                Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000)
        );
        return result;
    }

    /** 只记录粗粒度调用方类型，不把真实 userId、tenantId 或完整角色集合写入普通日志。 */
    private String callerType(AuthenticatedPrincipal principal) {
        if (principal == null || !principal.isAuthenticated()) {
            return "ANONYMOUS";
        }
        if (principal.hasRole(SecurityRoles.MCP_CLIENT)) {
            return "MCP_CLIENT";
        }
        if (principal.hasRole(SecurityRoles.ADMIN)) {
            return "ADMIN";
        }
        return "AUTHENTICATED";
    }
}
