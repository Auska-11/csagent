package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSearchResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSummary;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSidecarProperties;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闲鱼租户商品目录：product.search / product.lookup 以商品知识文档为数据源，
 * 演示目录不再对 xianyu 租户可见（X3“商品文档生效”）。
 */
class XianyuTenantProductCatalogTest {

    private static final String ITEM_CONTENT = """
            # 森海塞尔MOMENTUM二代头戴耳机

            > 闲鱼商品快照自动生成（2026-09-04）。人工审校后移动到 src/main/resources/knowledge/product/ 并重启应用生效。

            关键词：森海塞尔MOMENTUM二代头戴耳机、1079979582985

            ## 基本信息

            - 商品 ID：1079979582985
            - 价格：¥265.0
            - 库存：1

            ## 商品描述

            森海塞尔MOMENTUM 2代头戴式蓝牙耳机，支持蓝牙和有线连接，米白+黑色配色。目前功能未测试，耳罩表面可见使用痕迹。
            """;

    @Test
    void xianyuTenantSearchMapsKnowledgeDocumentToProductSummary() {
        LocalProductCatalogCapabilityProvider provider = provider(xianyuItemDoc());

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_SEARCH, java.util.Map.of("keyword", "耳机")),
                xianyuContext()
        );

        ProductSearchResult products = (ProductSearchResult) result.getData();
        assertTrue(result.isSuccess());
        assertEquals(1, products.getCount());
        ProductSummary item = products.getProducts().get(0);
        assertEquals("1079979582985", item.getProductId());
        assertEquals("森海塞尔MOMENTUM二代头戴耳机", item.getName());
        assertEquals(265, item.getPrice());
        assertEquals(1, item.getStock());
        assertTrue(item.isInStock());
        assertTrue(item.getDescription().contains("支持蓝牙和有线连接"));
        assertTrue(item.getDescription().contains("功能未测试"));
    }

    @Test
    void xianyuTenantSearchExcludesPolicyDocumentsWithoutXianyuCategory() {
        KnowledgeDocument policyDoc = new KnowledgeDocument(
                "policy/return_policy.md",
                "policy",
                "退换货政策",
                "## 政策\n\n耳机类商品七天无理由退货。",
                List.of("耳机", "退货"),
                KnowledgeType.POLICY,
                "",
                "",
                "classpath:/knowledge/policy/return_policy.md",
                "",
                null
        );
        LocalProductCatalogCapabilityProvider provider = provider(xianyuItemDoc(), policyDoc);

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_SEARCH, java.util.Map.of("keyword", "耳机")),
                xianyuContext()
        );

        ProductSearchResult products = (ProductSearchResult) result.getData();
        assertTrue(result.isSuccess());
        assertEquals(1, products.getCount());
        assertEquals("1079979582985", products.getProducts().get(0).getProductId());
    }

    @Test
    void xianyuTenantLookupFindsItemAndRejectsMiss() {
        LocalProductCatalogCapabilityProvider provider = provider(xianyuItemDoc());

        CapabilityResult<?> found = provider.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_LOOKUP, java.util.Map.of("product_name", "森海塞尔")),
                xianyuContext()
        );
        CapabilityResult<?> missing = provider.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_LOOKUP, java.util.Map.of("product_name", "钢琴")),
                xianyuContext()
        );

        ProductSummary item = (ProductSummary) found.getData();
        assertTrue(found.isSuccess());
        assertEquals("森海塞尔MOMENTUM二代头戴耳机", item.getName());
        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, missing.getOutcome());
        assertEquals("PRODUCT_NOT_FOUND", missing.getErrorCode());
        assertNull(missing.getData());
    }

    @Test
    void xianyuTenantDegradesToZeroWhenPriceOrStockLineMissing() {
        KnowledgeDocument degraded = new KnowledgeDocument(
                "product/9001.md",
                "product",
                "未标注价格的键盘",
                "## 商品描述\n\n机械键盘，成色良好。",
                List.of("机械键盘"),
                KnowledgeType.POLICY,
                "xianyu",
                "",
                "xianyu-item-snapshot",
                "",
                null
        );
        LocalProductCatalogCapabilityProvider provider = provider(degraded);

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_SEARCH, java.util.Map.of("keyword", "键盘")),
                xianyuContext()
        );

        ProductSummary item = ((ProductSearchResult) result.getData()).getProducts().get(0);
        assertTrue(result.isSuccess());
        assertEquals("9001", item.getProductId());
        assertEquals(0, item.getPrice());
        assertEquals(0, item.getStock());
    }

    @Test
    void defaultTenantStillReadsDemoRepository() {
        LocalProductCatalogCapabilityProvider provider = provider(xianyuItemDoc());

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall(CapabilityIds.PRODUCT_SEARCH, java.util.Map.of("keyword", "耳机")),
                tenantContext("default")
        );

        ProductSearchResult products = (ProductSearchResult) result.getData();
        assertTrue(result.isSuccess());
        assertEquals(1, products.getCount());
        assertEquals("蓝牙耳机 Pro Max", products.getProducts().get(0).getName());
    }

    private KnowledgeDocument xianyuItemDoc() {
        return new KnowledgeDocument(
                "product/1079979582985.md",
                "product",
                "森海塞尔MOMENTUM二代头戴耳机",
                ITEM_CONTENT,
                List.of("森海塞尔MOMENTUM二代头戴耳机", "1079979582985"),
                KnowledgeType.POLICY,
                XianyuKnowledgeProductCatalog.PRODUCT_CATEGORY,
                "",
                "xianyu-item-snapshot",
                "1",
                null
        );
    }

    private LocalProductCatalogCapabilityProvider provider(KnowledgeDocument... documents) {
        return new LocalProductCatalogCapabilityProvider(
                new ProductRepository(),
                new KnowledgeTools(List.of(documents)),
                new XianyuSidecarProperties()
        );
    }

    private ExecutionContext xianyuContext() {
        return tenantContext("xianyu");
    }

    private ExecutionContext tenantContext(String tenantId) {
        return new ExecutionContext(
                "test-trace",
                AuthenticatedPrincipal.anonymous(),
                tenantId,
                "test-conversation",
                "",
                null
        );
    }
}
