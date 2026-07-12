package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.local.ToolRegistryCapabilityProvider;
import com.smartcs.smart_cs_agent.mcp.server.McpServerAdapter;
import com.smartcs.smart_cs_agent.mcp.server.McpServerSecurityProperties;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.tools.*;
import com.smartcs.smart_cs_agent.security.SecurityRoles;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP Adapter 接口测试。
 *
 * 这里不启动完整 Spring 容器，而是直接实例化 Controller。
 * 这样测试更轻，也能专注验证“协议边界是否稳定”。
 */
class McpToolControllerTest {

    @Test
    void shouldListMcpTools() {
        McpToolController controller = new McpToolController(createAdapter());

        Map<String, Object> response = controller.listTools();

        assertEquals("mcp-adapter-v2", response.get("protocol"));
        assertEquals(9, response.get("count"));

        List<?> tools = assertInstanceOf(List.class, response.get("tools"));
        CapabilityDefinition orderLookup = tools.stream()
                .map(CapabilityDefinition.class::cast)
                .filter(tool -> "order.lookup".equals(tool.getId()))
                .findFirst()
                .orElseThrow();

        assertTrue(orderLookup.getInputSchema().containsKey("properties"));
        assertTrue(orderLookup.getOutputSchema().containsKey("properties"));
        assertEquals("1.0.0", orderLookup.getVersion());
    }

    @Test
    void shouldCallOrderLookupTool() {
        McpToolController controller = new McpToolController(createAdapter());

        McpToolCallRequest request = authenticatedRequest("order.lookup", Map.of("order_id", "ORD-001"));
        McpToolResponse response = controller.callTool(request, adminCustomer());

        assertTrue(response.isSuccess());
        assertEquals("order.lookup", response.getToolName());
        assertNull(response.getErrorCode());
        assertNotNull(response.getData());
        assertTrue(response.getMessage().contains("ORD-001"));
    }

    @Test
    void shouldReturnBadRequestWhenToolNameIsMissing() {
        McpToolController controller = new McpToolController(createAdapter());

        McpToolResponse response = controller.callTool(
                new McpToolCallRequest("", Map.of()),
                adminCustomer()
        );

        assertFalse(response.isSuccess());
        assertEquals("BAD_REQUEST", response.getErrorCode());
    }

    @Test
    void shouldReturnFailureWhenToolDoesNotExist() {
        McpToolController controller = new McpToolController(createAdapter());

        McpToolResponse response = controller.callTool(
                authenticatedRequest("not.exist", Map.of()),
                adminCustomer()
        );

        assertFalse(response.isSuccess());
        assertEquals("not.exist", response.getToolName());
        assertEquals("CAPABILITY_NOT_FOUND", response.getErrorCode());
    }

    @Test
    void shouldRejectUnauthenticatedExternalToolCall() {
        McpToolController controller = new McpToolController(createAdapter());

        McpToolResponse response = controller.callTool(
                new McpToolCallRequest("order.lookup", Map.of("order_id", "ORD-001")),
                null
        );

        assertFalse(response.isSuccess());
        assertEquals("MCP_AUTHENTICATION_REQUIRED", response.getErrorCode());
    }

    @Test
    void servicePrincipalShouldRequireSignedDelegationForIdentityCapability() {
        McpToolController controller = new McpToolController(createAdapter());

        McpToolResponse response = controller.callTool(
                authenticatedRequest("order.lookup", Map.of("order_id", "ORD-001")),
                AuthenticatedPrincipal.authenticated(
                        "mcp-service",
                        Set.of(SecurityRoles.MCP_CLIENT)
                )
        );

        assertFalse(response.isSuccess());
        assertEquals("MCP_DELEGATION_REQUIRED", response.getErrorCode());
    }

    private McpToolCallRequest authenticatedRequest(String name, Map<String, Object> arguments) {
        McpToolCallRequest request = new McpToolCallRequest(name, arguments);
        request.setRequestId("mcp-rest-test");
        request.setAuthToken("token-zhangsan");
        request.setUserId("USER-001");
        request.setTenantId("default");
        request.setConversationId("conv-mcp-test");
        request.setIdempotencyKey("idem-mcp-test");
        return request;
    }

    private McpServerAdapter createAdapter() {
        ToolRegistry registry = createRegistry();
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(
                new ToolRegistryCapabilityProvider(registry, true)
        ));
        return new McpServerAdapter(
                gateway,
                new McpServerSecurityProperties()
        );
    }

    private AuthenticatedPrincipal adminCustomer() {
        return AuthenticatedPrincipal.authenticated(
                "USER-001",
                Set.of(SecurityRoles.CUSTOMER, SecurityRoles.ADMIN)
        );
    }

    private ToolRegistry createRegistry() {
        ProductRepository productRepository = new ProductRepository();
        OrderRepository orderRepository = new OrderRepository();
        ReturnRepository returnRepository = new ReturnRepository();
        UserRepository userRepository = new UserRepository();

        return new ToolRegistry(
                new OrderTools(orderRepository, productRepository),
                new ProductTools(productRepository),
                new ReturnTools(returnRepository, orderRepository, productRepository),
                new UserTools(userRepository),
                createKnowledgeTools(),
                new HandoffTools()
        );
    }

    private KnowledgeTools createKnowledgeTools() {
        KnowledgeDocument returnPolicy = new KnowledgeDocument(
                "policy/return_policy.md",
                "policy",
                "退换货政策",
                "超过 7 天后，不再默认支持七天无理由退货。若商品存在质量问题，可以继续申请售后检测。",
                List.of("退货", "七天", "超过七天", "质量问题")
        );
        return new KnowledgeTools(List.of(returnPolicy));
    }
}
