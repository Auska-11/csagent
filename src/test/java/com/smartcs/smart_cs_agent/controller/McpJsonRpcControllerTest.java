package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
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

class McpJsonRpcControllerTest {

    @Test
    void shouldInitializeMcpServer() {
        McpJsonRpcController controller = new McpJsonRpcController(createAdapter());
        McpJsonRpcRequest request = request(1, "initialize", Map.of());

        McpJsonRpcResponse response = controller.handle(request, adminCustomer());

        assertNull(response.getError());
        Map<?, ?> result = assertInstanceOf(Map.class, response.getResult());
        assertEquals("2024-11-05", result.get("protocolVersion"));
        assertTrue(result.containsKey("capabilities"));
    }

    @Test
    void shouldListToolsByJsonRpc() {
        McpJsonRpcController controller = new McpJsonRpcController(createAdapter());
        McpJsonRpcRequest request = request(2, "tools/list", Map.of());

        McpJsonRpcResponse response = controller.handle(request, adminCustomer());

        assertNull(response.getError());
        Map<?, ?> result = assertInstanceOf(Map.class, response.getResult());
        List<?> tools = assertInstanceOf(List.class, result.get("tools"));
        assertEquals(9, tools.size());
    }

    @Test
    void shouldCallToolByJsonRpc() {
        McpJsonRpcController controller = new McpJsonRpcController(createAdapter());
        McpJsonRpcRequest request = request(3, "tools/call", Map.of(
                "name", "order.lookup",
                "arguments", Map.of("order_id", "ORD-001"),
                "_meta", authenticatedMeta()
        ));

        McpJsonRpcResponse response = controller.handle(request, adminCustomer());

        assertNull(response.getError());
        Map<?, ?> result = assertInstanceOf(Map.class, response.getResult());
        assertEquals(false, result.get("isError"));
        assertNotNull(result.get("structuredContent"));
    }

    @Test
    void shouldReturnMethodNotFound() {
        McpJsonRpcController controller = new McpJsonRpcController(createAdapter());
        McpJsonRpcRequest request = request(4, "unknown/method", Map.of());

        McpJsonRpcResponse response = controller.handle(request, adminCustomer());

        assertNotNull(response.getError());
        assertEquals(-32601, response.getError().getCode());
    }

    @Test
    void shouldReturnToolErrorWhenCallParamsMissing() {
        McpJsonRpcController controller = new McpJsonRpcController(createAdapter());
        McpJsonRpcRequest request = request(5, "tools/call", null);

        McpJsonRpcResponse response = controller.handle(request, adminCustomer());

        assertNull(response.getError());
        Map<?, ?> result = assertInstanceOf(Map.class, response.getResult());
        assertEquals(true, result.get("isError"));
        Map<?, ?> meta = assertInstanceOf(Map.class, result.get("_meta"));
        assertEquals("BAD_REQUEST", meta.get("errorCode"));
    }

    private McpJsonRpcRequest request(Object id, String method, Map<String, Object> params) {
        McpJsonRpcRequest request = new McpJsonRpcRequest();
        request.setId(id);
        request.setMethod(method);
        request.setParams(params);
        return request;
    }

    private Map<String, Object> authenticatedMeta() {
        return Map.of(
                "requestId", "json-rpc-test",
                "authToken", "token-zhangsan",
                "userId", "USER-001",
                "tenantId", "default",
                "conversationId", "conv-json-rpc",
                "idempotencyKey", "idem-json-rpc"
        );
    }

    private McpServerAdapter createAdapter() {
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(
                new ToolRegistryCapabilityProvider(createRegistry(), true)
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
