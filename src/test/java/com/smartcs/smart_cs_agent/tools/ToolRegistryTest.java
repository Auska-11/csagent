package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolRegistry 单元测试。
 *
 * 这里验证的是 MCP-style 工具层的核心能力：
 * - 工具可以被注册和发现
 * - 正常参数可以成功调用
 * - 缺少必填参数时返回结构化失败
 * - 未知工具不会抛异常，而是返回 ToolResult 失败结果
 */
class ToolRegistryTest {

    @Test
    void shouldExecuteRegisteredTool() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "order_lookup",
                Map.of("order_id", "ORD-001")
        ));

        assertTrue(result.isSuccess());
        assertEquals("order_lookup", result.getToolName());
        assertNull(result.getErrorCode());
        assertTrue(result.getMessage().contains("ORD-001"));

        OrderToolData data = assertInstanceOf(OrderToolData.class, result.getData());
        assertTrue(data.isFound());
        assertEquals("ORD-001", data.getOrderId());
        assertEquals("蓝牙耳机 Pro Max", data.getProductName());
        assertEquals("已发货", data.getStatus());
    }

    @Test
    void shouldReturnFailureWhenRequiredArgumentMissing() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "order_lookup",
                Map.of()
        ));

        assertFalse(result.isSuccess());
        assertEquals("MISSING_ARGUMENT", result.getErrorCode());
        assertTrue(result.getMessage().contains("order_id"));
    }

    @Test
    void shouldReturnFailureWhenToolNotFound() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "unknown_tool",
                Map.of()
        ));

        assertFalse(result.isSuccess());
        assertEquals("TOOL_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void shouldReturnTypedDataForUserOrders() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "user_orders",
                Map.of("user_id", "USER-001")
        ));

        assertTrue(result.isSuccess());
        UserOrdersToolData data = assertInstanceOf(UserOrdersToolData.class, result.getData());
        assertEquals("USER-001", data.getUserId());
        assertEquals(2, data.getCount());
        assertTrue(data.getOrders().stream()
                .anyMatch(order -> "ORD-001".equals(order.getOrderId())
                        && "蓝牙耳机 Pro Max".equals(order.getProductName())));
    }

    @Test
    void shouldListRegisteredTools() {
        ToolRegistry registry = createRegistry();

        List<ToolDefinition> tools = registry.listTools();
        assertTrue(tools
                .stream()
                .anyMatch(tool -> "return_apply".equals(tool.getName())));

        ToolDefinition returnApply = tools.stream()
                .filter(tool -> "return_apply".equals(tool.getName()))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("order_id", "reason"), returnApply.getRequiredParameters());
        assertEquals(2, returnApply.getParameterSchemas().size());

        ToolParameterDefinition orderId = returnApply.getParameterSchemas().get(0);
        assertEquals("order_id", orderId.getName());
        assertEquals("string", orderId.getType());
        assertTrue(orderId.isRequired());
        assertEquals("ORD-004", orderId.getExample());

        // 这个 inputSchema 是后续对接 MCP tools/list 或前端工具面板的重要基础。
        Map<?, ?> inputSchema = returnApply.toInputSchema();
        assertEquals("object", inputSchema.get("type"));
        assertEquals(List.of("order_id", "reason"), inputSchema.get("required"));

        Map<?, ?> properties = assertInstanceOf(Map.class, inputSchema.get("properties"));
        assertTrue(properties.containsKey("order_id"));
    }

    @Test
    void shouldExecuteKnowledgeSearchTool() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "knowledge_search",
                Map.of("query", "超过七天还能退吗")
        ));

        assertTrue(result.isSuccess());
        assertEquals("knowledge_search", result.getToolName());
        assertTrue(result.getMessage().contains("退换货政策"));
        assertTrue(result.getData() instanceof List<?>);
    }

    @Test
    void shouldReturnStructuredDataForProductSearch() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "product_search",
                Map.of("keyword", "蓝牙")
        ));

        assertTrue(result.isSuccess());
        ProductSearchToolData data = assertInstanceOf(ProductSearchToolData.class, result.getData());
        assertEquals("蓝牙", data.getKeyword());
        assertEquals(1, data.getCount());

        ProductItemData firstProduct = data.getProducts().get(0);
        assertEquals("PROD-001", firstProduct.getProductId());
        assertTrue(firstProduct.isInStock());
    }

    @Test
    void shouldReturnTypedDataForProductInfo() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "product_info",
                Map.of("product_name", "蓝牙")
        ));

        assertTrue(result.isSuccess());
        ProductInfoToolData data = assertInstanceOf(ProductInfoToolData.class, result.getData());
        assertTrue(data.isFound());
        assertEquals("蓝牙", data.getQuery());
        assertEquals("PROD-001", data.getProduct().getProductId());
        assertEquals("蓝牙耳机 Pro Max", data.getProduct().getName());
    }

    @Test
    void shouldReturnStructuredDataForReturnApply() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "return_apply",
                Map.of(
                        "order_id", "ORD-002",
                        "reason", "不想要了"
                )
        ));

        assertTrue(result.isSuccess());
        ReturnToolData data = assertInstanceOf(ReturnToolData.class, result.getData());
        assertEquals(true, data.getSubmitted());
        assertEquals("RET-002", data.getReturnId());
        assertEquals("ORD-002", data.getOrderId());
        assertEquals("不想要了", data.getReason());
        assertEquals("Under Review", data.getStatus());
        assertEquals("1-2 business days", data.getExpectedReviewTime());
    }

    @Test
    void shouldReturnStructuredDataForUserLookup() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "user_lookup",
                Map.of("token", "USER-001")
        ));

        assertTrue(result.isSuccess());
        UserToolData data = assertInstanceOf(UserToolData.class, result.getData());
        assertTrue(data.isFound());
        assertEquals("USER-001", data.getUserId());
        assertEquals("张三", data.getName());
    }

    @Test
    void shouldExecuteHandoffCreateTool() {
        ToolRegistry registry = createRegistry();

        ToolResult result = registry.execute(new ToolCall(
                "handoff_create",
                Map.of(
                        "user_id", "USER-001",
                        "conversation_id", "conv-test",
                        "reason", "COMPLAINT",
                        "summary", "用户强烈投诉，需要人工处理"
                )
        ));

        assertTrue(result.isSuccess());
        assertEquals("handoff_create", result.getToolName());
        assertTrue(result.getMessage().contains("工单号"));
        HandoffTicketData data = assertInstanceOf(HandoffTicketData.class, result.getData());
        assertEquals("USER-001", data.getUserId());
        assertEquals("conv-test", data.getConversationId());
        assertEquals("WAITING_HUMAN_AGENT", data.getStatus());
        assertTrue(data.getTicketId().startsWith("HD-"));
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
