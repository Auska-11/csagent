package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
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
                WorkflowTestSupport.localCapabilityGateway()
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
        Map<?, ?> outputProperties = assertInstanceOf(Map.class, outputSchema.get("properties"));
        assertTrue(outputProperties.containsKey("order_id"));
        assertTrue(outputProperties.containsKey("tracking_no"));
    }

}
