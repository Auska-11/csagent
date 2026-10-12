package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSearchResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSummary;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.local.LocalProductCatalogCapabilityProvider;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSidecarProperties;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 商品目录独立 Provider 的定义、查询和错误契约测试。 */
class LocalProductCatalogCapabilityProviderTest {

    @Test
    void shouldExposePublicReadOnlySearchAndLookupDefinitions() {
        LocalProductCatalogCapabilityProvider provider = provider();
        List<CapabilityDefinition> definitions = provider.definitions().stream().toList();

        assertEquals(
                List.of(CapabilityIds.PRODUCT_SEARCH, CapabilityIds.PRODUCT_LOOKUP),
                definitions.stream().map(CapabilityDefinition::getId).toList()
        );
        for (CapabilityDefinition definition : definitions) {
            assertEquals("2.0.0", definition.getVersion());
            assertFalse(definition.isRequiresIdentity());
            assertFalse(definition.isRequiresAuthentication());
            assertFalse(definition.isSideEffect());
            assertFalse(definition.isIdempotent());
            assertEquals(CapabilityRiskLevel.READ_ONLY, definition.getRiskLevel());
        }
    }

    @Test
    void shouldSearchSpecificProductAndListWholeCatalogWithEmptyKeyword() {
        DefaultCapabilityGateway gateway = gateway();

        CapabilityResult<?> specific = gateway.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_SEARCH, Map.of("keyword", "蓝牙")),
                ExecutionContext.legacy()
        );
        CapabilityResult<?> all = gateway.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_SEARCH, Map.of("keyword", "")),
                ExecutionContext.legacy()
        );

        ProductSearchResult specificProducts = (ProductSearchResult) specific.getData();
        ProductSearchResult allProducts = (ProductSearchResult) all.getData();
        assertTrue(specific.isSuccess());
        assertEquals(1, specificProducts.getCount());
        assertEquals("蓝牙耳机 Pro Max", specificProducts.getProducts().get(0).getName());
        assertEquals(4, allProducts.getCount());
    }

    @Test
    void shouldLookupProductAsStableSummaryAndRejectMissingProduct() {
        DefaultCapabilityGateway gateway = gateway();

        CapabilityResult<?> found = gateway.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_LOOKUP, Map.of("product_name", "手表")),
                ExecutionContext.legacy()
        );
        CapabilityResult<?> missing = gateway.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_LOOKUP, Map.of("product_name", "不存在的商品")),
                ExecutionContext.legacy()
        );

        ProductSummary product = (ProductSummary) found.getData();
        assertTrue(found.isSuccess());
        assertEquals("PROD-002", product.getProductId());
        assertEquals(new java.math.BigDecimal("1299"), product.getPrice());
        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, missing.getOutcome());
        assertEquals("PRODUCT_NOT_FOUND", missing.getErrorCode());
        assertNull(missing.getData());
    }

    private LocalProductCatalogCapabilityProvider provider() {
        return new LocalProductCatalogCapabilityProvider(
                new ProductRepository(),
                org.mockito.Mockito.mock(XianyuItemFactService.class)
        );
    }

    private DefaultCapabilityGateway gateway() {
        return new DefaultCapabilityGateway(List.of(provider()));
    }
}
