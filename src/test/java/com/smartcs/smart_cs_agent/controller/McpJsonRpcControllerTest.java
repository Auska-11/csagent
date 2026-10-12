package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.mcp.server.McpServerAdapter;
import com.smartcs.smart_cs_agent.mcp.server.McpServerSecurityProperties;
import com.smartcs.smart_cs_agent.security.SecurityRoles;
import com.smartcs.smart_cs_agent.testing.WorkflowTestSupport;
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
        request.setJsonrpc("2.0");
        request.setId(id);
        request.setMethod(method);
        request.setParams(params);
        return request;
    }

    @Test
    void shouldAcknowledgePingWithEmptyResult() {
        McpJsonRpcController controller = new McpJsonRpcController(createAdapter());
        McpJsonRpcResponse response = controller.handle(request(6, "ping", Map.of()), adminCustomer());
        assertNull(response.getError());
        assertEquals(Map.of(), response.getResult());
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
        return new McpServerAdapter(
                WorkflowTestSupport.localCapabilityGateway(),
                new McpServerSecurityProperties()
        );
    }

    private AuthenticatedPrincipal adminCustomer() {
        return AuthenticatedPrincipal.authenticated(
                "USER-001",
                Set.of(SecurityRoles.CUSTOMER, SecurityRoles.ADMIN)
        );
    }

}
