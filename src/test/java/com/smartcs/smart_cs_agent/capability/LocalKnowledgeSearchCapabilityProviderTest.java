package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.local.LocalKnowledgeSearchCapabilityProvider;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSidecarProperties;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 知识库独立 Provider 的公开定义、缺参和稳定结果转换测试。 */
class LocalKnowledgeSearchCapabilityProviderTest {

    @Test
    void shouldExposePublicReadOnlyDefinition() {
        LocalKnowledgeSearchCapabilityProvider provider = provider();
        CapabilityDefinition definition = provider.definitions().iterator().next();

        assertEquals(CapabilityIds.KNOWLEDGE_SEARCH, definition.getId());
        assertEquals("2.0.0", definition.getVersion());
        assertFalse(definition.isRequiresIdentity());
        assertFalse(definition.isRequiresAuthentication());
        assertFalse(definition.isSideEffect());
        assertFalse(definition.isIdempotent());
        assertEquals(CapabilityRiskLevel.READ_ONLY, definition.getRiskLevel());
        Map<?, ?> properties = (Map<?, ?>) definition.getInputSchema().get("properties");
        assertTrue(properties.containsKey("knowledge_type"));
        assertTrue(properties.containsKey("product_category"));
        assertTrue(properties.containsKey("product_model"));
    }

    @Test
    void shouldConvertInternalSearchDetailsToStableKnowledgeHits() {
        CapabilityResult<?> result = gateway().invoke(
                new CapabilityCall(CapabilityIds.KNOWLEDGE_SEARCH, Map.of("query", "超过七天还能退吗")),
                ExecutionContext.legacy()
        );

        assertTrue(result.isSuccess());
        assertEquals(LocalKnowledgeSearchCapabilityProvider.PROVIDER_ID, result.getProviderId());
        List<?> hits = (List<?>) result.getData();
        KnowledgeSearchHit first = (KnowledgeSearchHit) hits.get(0);
        assertEquals("policy/return_policy.md", first.getDocumentId());
        assertEquals("POLICY", first.getKnowledgeType());
        assertFalse(first.getChunkId().isBlank());
        assertEquals("退换货政策", first.getTitle());
        assertTrue(first.getScore() > 0);
        assertTrue(first.getSnippet().contains("7 天"));
    }

    @Test
    void shouldRejectBlankQueryAsBusinessInputError() {
        CapabilityResult<?> result = gateway().invoke(
                new CapabilityCall(CapabilityIds.KNOWLEDGE_SEARCH, Map.of("query", "  ")),
                ExecutionContext.legacy()
        );

        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, result.getOutcome());
        assertEquals("MISSING_ARGUMENT", result.getErrorCode());
        assertNull(result.getData());
    }

    private LocalKnowledgeSearchCapabilityProvider provider() {
        KnowledgeDocument returnPolicy = new KnowledgeDocument(
                "policy/return_policy.md",
                "policy",
                "退换货政策",
                "超过 7 天后，不再默认支持七天无理由退货。质量问题可以继续申请售后检测。",
                List.of("退货", "七天", "超过七天", "质量问题")
        );
        return new LocalKnowledgeSearchCapabilityProvider(new KnowledgeTools(List.of(returnPolicy)), new XianyuSidecarProperties(), org.mockito.Mockito.mock(XianyuItemFactService.class));
    }

    private DefaultCapabilityGateway gateway() {
        return new DefaultCapabilityGateway(List.of(provider()));
    }
}
