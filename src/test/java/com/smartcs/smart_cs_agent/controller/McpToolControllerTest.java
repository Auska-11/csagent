package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.tools.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        McpToolController controller = new McpToolController(new ToolManager(createRegistry()));

        Map<String, Object> response = controller.listTools();

        assertEquals("mcp-adapter-v1", response.get("protocol"));
        assertEquals(9, response.get("count"));

        List<?> tools = assertInstanceOf(List.class, response.get("tools"));
        ToolDefinition orderLookup = tools.stream()
                .map(ToolDefinition.class::cast)
                .filter(tool -> "order_lookup".equals(tool.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals("OrderToolData", orderLookup.getOutputType());
        assertTrue(orderLookup.getInputSchema().containsKey("properties"));
        assertTrue(orderLookup.getOutputSchema().containsKey("properties"));
    }

    @Test
    void shouldCallOrderLookupTool() {
        McpToolController controller = new McpToolController(new ToolManager(createRegistry()));

        McpToolResponse response = controller.callTool(new McpToolCallRequest(
                "order_lookup",
                Map.of("order_id", "ORD-001")
        ));

        assertTrue(response.isSuccess());
        assertEquals("order_lookup", response.getToolName());
        assertNull(response.getErrorCode());
        assertNotNull(response.getData());
        assertTrue(response.getMessage().contains("ORD-001"));
    }

    @Test
    void shouldReturnBadRequestWhenToolNameIsMissing() {
        McpToolController controller = new McpToolController(new ToolManager(createRegistry()));

        McpToolResponse response = controller.callTool(new McpToolCallRequest("", Map.of()));

        assertFalse(response.isSuccess());
        assertEquals("BAD_REQUEST", response.getErrorCode());
    }

    @Test
    void shouldReturnFailureWhenToolDoesNotExist() {
        McpToolController controller = new McpToolController(new ToolManager(createRegistry()));

        McpToolResponse response = controller.callTool(new McpToolCallRequest(
                "not_exist_tool",
                Map.of()
        ));

        assertFalse(response.isSuccess());
        assertEquals("not_exist_tool", response.getToolName());
        assertEquals("TOOL_NOT_FOUND", response.getErrorCode());
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
