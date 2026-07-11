package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.capability.local.ToolRegistryCapabilityProvider;
import com.smartcs.smart_cs_agent.tools.OrderToolData;
import com.smartcs.smart_cs_agent.tools.KnowledgeSearchResult;
import com.smartcs.smart_cs_agent.tools.ToolCall;
import com.smartcs.smart_cs_agent.tools.ToolDefinition;
import com.smartcs.smart_cs_agent.tools.ToolRegistry;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证旧 ToolRegistry 能在不改变业务行为的前提下接入新 CapabilityProvider 协议。
 */
class ToolRegistryCapabilityProviderTest {

    @Test
    void shouldExposeToolDefinitionAndDelegateExecution() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        ToolDefinition legacyDefinition = new ToolDefinition(
                "order_lookup",
                "查询订单",
                Map.of("order_id", "订单号"),
                List.of("order_id")
        );
        when(toolRegistry.listTools()).thenReturn(List.of(legacyDefinition));
        when(toolRegistry.execute(any(ToolCall.class))).thenReturn(
                ToolResult.success("order_lookup", "订单已找到", Map.of("order_id", "ORD-001"))
        );
        ToolRegistryCapabilityProvider provider = new ToolRegistryCapabilityProvider(toolRegistry);

        CapabilityDefinition definition = provider.definitions().iterator().next();
        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall("order_lookup", Map.of("order_id", "ORD-001")),
                ExecutionContext.legacy()
        );

        // Provider 对外暴露稳定 canonical ID；委托旧 ToolRegistry 时才翻译回 order_lookup。
        assertEquals("order.lookup", definition.getId());
        assertTrue(definition.getInputSchema().containsKey("required"));
        assertTrue(result.isSuccess());
        assertEquals(Map.of("order_id", "ORD-001"), result.getData());

        ArgumentCaptor<ToolCall> captor = ArgumentCaptor.forClass(ToolCall.class);
        verify(toolRegistry).execute(captor.capture());
        assertEquals("order_lookup", captor.getValue().getName());
        assertEquals("ORD-001", captor.getValue().getArguments().get("order_id"));
    }

    @Test
    void shouldMapMissingBusinessObjectToBusinessRejectedInsteadOfTechnicalSuccess() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.execute(any(ToolCall.class))).thenReturn(
                ToolResult.success(
                        "order_lookup",
                        "Order ORD-999 not found",
                        OrderToolData.notFound("ORD-999")
                )
        );
        ToolRegistryCapabilityProvider provider = new ToolRegistryCapabilityProvider(toolRegistry);

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall("order.lookup", Map.of("order_id", "ORD-999")),
                ExecutionContext.legacy()
        );

        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, result.getOutcome());
        assertEquals("ORDER_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void shouldMapLegacyKnowledgeDataToStableCapabilityContract() {
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        when(toolRegistry.execute(any(ToolCall.class))).thenReturn(
                ToolResult.success(
                        "knowledge_search",
                        "命中保修政策",
                        List.of(new KnowledgeSearchResult(
                                "policy/warranty_policy.md",
                                "policy",
                                "保修政策",
                                42,
                                "电子配件通常提供 1 年有限保修服务。"
                        ))
                )
        );
        ToolRegistryCapabilityProvider provider = new ToolRegistryCapabilityProvider(toolRegistry);

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall("knowledge.search", Map.of("query", "保修多久")),
                ExecutionContext.legacy()
        );

        assertTrue(result.isSuccess());
        Object first = ((List<?>) result.getData()).get(0);
        assertTrue(first instanceof KnowledgeSearchHit);
        assertEquals("保修政策", ((KnowledgeSearchHit) first).getTitle());
    }
}
