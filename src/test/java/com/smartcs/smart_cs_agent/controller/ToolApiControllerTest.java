package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.tools.HandoffTools;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import com.smartcs.smart_cs_agent.tools.OrderTools;
import com.smartcs.smart_cs_agent.tools.ProductTools;
import com.smartcs.smart_cs_agent.tools.ReturnTools;
import com.smartcs.smart_cs_agent.tools.ToolRegistry;
import com.smartcs.smart_cs_agent.tools.UserTools;
import com.smartcs.smart_cs_agent.testing.WorkflowTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调试接口测试。
 *
 * 这里不启动完整 Spring 容器，而是直接实例化 Controller。
 * 这样测试更轻，也能专注验证接口返回结构。
 */
class ToolApiControllerTest {

    @Test
    void shouldListToolsWithInputSchema() {
        ToolApiController controller = new ToolApiController(
                WorkflowTestSupport.toolRegistryGateway(createRegistry())
        );

        Map<String, Object> response = controller.listTools();

        assertEquals(9, response.get("count"));

        List<?> tools = assertInstanceOf(List.class, response.get("tools"));
        CapabilityDefinition orderLookup = tools.stream()
                .map(CapabilityDefinition.class::cast)
                .filter(tool -> "order.lookup".equals(tool.getId()))
                .findFirst()
                .orElseThrow();

        Map<?, ?> inputSchema = orderLookup.getInputSchema();
        assertEquals("object", inputSchema.get("type"));

        Map<?, ?> properties = assertInstanceOf(Map.class, inputSchema.get("properties"));
        assertTrue(properties.containsKey("order_id"));
        assertEquals(List.of("order_id"), inputSchema.get("required"));

        Map<?, ?> outputSchema = orderLookup.getOutputSchema();
        assertEquals("object", outputSchema.get("type"));
        assertEquals("OrderToolData", outputSchema.get("dto"));

        Map<?, ?> outputProperties = assertInstanceOf(Map.class, outputSchema.get("properties"));
        assertTrue(outputProperties.containsKey("order_id"));
        assertTrue(outputProperties.containsKey("tracking_no"));
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
