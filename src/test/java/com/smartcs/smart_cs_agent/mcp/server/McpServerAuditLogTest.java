package com.smartcs.smart_cs_agent.mcp.server;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCatalog;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.security.SecurityRoles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 审计日志安全测试。
 *
 * <p>这里捕获真实 SLF4J 输出，验证的不只是某个清洗函数，而是从 Adapter 返回结果到日志文本的
 * 完整边界。业务参数和身份数据即使真实参与执行，也不能出现在普通日志中。</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class McpServerAuditLogTest {

    @Test
    void shouldAuditSuccessBusinessRejectionAndTechnicalFailureWithoutSensitiveData(
            CapturedOutput output) {
        McpServerAdapter adapter = adapter();
        AuthenticatedPrincipal admin = admin();
        McpInvocationMetadata metadata = sensitiveMetadata("request password=hidden-request-secret");
        Map<String, Object> arguments = Map.of(
                "password", "hidden-argument-secret",
                "phone", "13800138000",
                "prompt", "hidden-user-prompt"
        );

        adapter.invoke("public.success", arguments, metadata, admin);
        adapter.invoke("public.business-rejected", arguments, metadata, admin);
        adapter.invoke("public.technical-failure", arguments, metadata, admin);

        String logs = output.getOut();
        assertTrue(logs.contains("outcome=SUCCESS"));
        assertTrue(logs.contains("outcome=BUSINESS_REJECTED"));
        assertTrue(logs.contains("outcome=TECHNICAL_FAILURE"));
        assertTrue(logs.contains("errorCode=ORDER_NOT_FOUND"));
        assertTrue(logs.contains("errorCode=PROVIDER_UNAVAILABLE"));
        assertFalse(logs.contains("hidden-request-secret"));
        assertFalse(logs.contains("hidden-argument-secret"));
        assertFalse(logs.contains("13800138000"));
        assertFalse(logs.contains("hidden-user-prompt"));
        assertFalse(logs.contains("body-auth-token"));
        assertFalse(logs.contains("admin-user"));
        assertFalse(logs.contains("tenant-a"));
        assertFalse(logs.contains("sensitive-conversation"));
        assertFalse(logs.contains("sensitive-idempotency-key"));
    }

    @Test
    void shouldAuditEverySecurityRejectionAndPreventLogLineInjection(CapturedOutput output) {
        McpServerAdapter adapter = adapter();

        adapter.invoke(
                "public.success\nFORGED password=hidden-capability-secret",
                Map.of("password", "hidden-argument-secret"),
                sensitiveMetadata("request\nFORGED password=hidden-request-secret"),
                null
        );
        adapter.invoke(
                "identity.read",
                Map.of(),
                McpInvocationMetadata.anonymous("delegation-request"),
                AuthenticatedPrincipal.authenticated(
                        "tenant-service",
                        "mcp-service",
                        Set.of(SecurityRoles.MCP_CLIENT)
                )
        );
        adapter.invoke(
                "public.success",
                Map.of(),
                new McpInvocationMetadata(
                        "identity-conflict-request",
                        "hidden-body-token",
                        "another-user",
                        "tenant-a",
                        "conversation",
                        "idempotency"
                ),
                admin()
        );

        String logs = output.getOut();
        assertTrue(logs.contains("errorCode=MCP_AUTHENTICATION_REQUIRED"));
        assertTrue(logs.contains("errorCode=MCP_DELEGATION_REQUIRED"));
        assertTrue(logs.contains("errorCode=MCP_IDENTITY_METADATA_REJECTED"));
        assertTrue(logs.contains("callerType=ANONYMOUS"));
        assertTrue(logs.contains("callerType=MCP_CLIENT"));
        assertTrue(logs.contains("callerType=ADMIN"));
        assertFalse(logs.contains("\nFORGED"));
        assertFalse(logs.contains("hidden-capability-secret"));
        assertFalse(logs.contains("hidden-request-secret"));
        assertFalse(logs.contains("hidden-argument-secret"));
        assertFalse(logs.contains("hidden-body-token"));
        assertFalse(logs.contains("another-user"));
    }

    private McpServerAdapter adapter() {
        return new McpServerAdapter(new AuditTestGateway(), new McpServerSecurityProperties());
    }

    private AuthenticatedPrincipal admin() {
        return AuthenticatedPrincipal.authenticated(
                "tenant-a",
                "admin-user",
                Set.of(SecurityRoles.ADMIN)
        );
    }

    private McpInvocationMetadata sensitiveMetadata(String requestId) {
        return new McpInvocationMetadata(
                requestId,
                "body-auth-token",
                "admin-user",
                "tenant-a",
                "sensitive-conversation",
                "sensitive-idempotency-key"
        );
    }

    private static final class AuditTestGateway implements CapabilityGateway {

        private final CapabilityCatalog catalog = new CapabilityCatalog(List.of(
                publicDefinition("public.success"),
                publicDefinition("public.business-rejected"),
                publicDefinition("public.technical-failure"),
                identityDefinition()
        ));

        @Override
        public CapabilityCatalog catalog() {
            return catalog;
        }

        @Override
        public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
            return switch (call.getCapabilityId()) {
                case "public.success" -> CapabilityResult.success(
                        call.getCapabilityId(), "audit-test-provider", "ok", Map.of("status", "ok")
                );
                case "public.business-rejected" -> CapabilityResult.businessRejected(
                        call.getCapabilityId(), "audit-test-provider", "ORDER_NOT_FOUND",
                        "Order does not exist", null
                );
                case "public.technical-failure" -> CapabilityResult.technicalFailure(
                        call.getCapabilityId(), "audit-test-provider", "PROVIDER_UNAVAILABLE",
                        "Provider is unavailable"
                );
                default -> CapabilityResult.technicalFailure(
                        call.getCapabilityId(), "audit-test-provider", "UNEXPECTED_TEST_CAPABILITY",
                        "Unexpected capability"
                );
            };
        }

        private static CapabilityDefinition publicDefinition(String id) {
            return new CapabilityDefinition(id, "审计测试公开能力", Map.of(), Map.of());
        }

        private static CapabilityDefinition identityDefinition() {
            return new CapabilityDefinition(
                    "identity.read",
                    "1.0.0",
                    "审计测试身份能力",
                    Map.of(),
                    Map.of(),
                    true,
                    true,
                    false,
                    false,
                    30_000,
                    CapabilityRiskLevel.READ_ONLY
            );
        }
    }
}
