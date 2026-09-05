package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSidecarProperties;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闲鱼租户知识检索隔离（压测 #3 根因）：xianyu 租户的 knowledge.search 只能命中
 * 商品快照文档（productcategory=xianyu），演示商品的故障排查文档（headphones 等）
 * 与演示政策文档不得进入真实买家渠道——与 X3 商品目录“演示数据不进真实渠道”同源。
 */
class XianyuTenantKnowledgeSearchTest {

    private static final String DEMO_TROUBLESHOOTING_CONTENT = """
            # 蓝牙耳机 Pro Max 使用与故障排查

            关键词：耳机, 蓝牙耳机, Pro Max, 故障排查, 重置, 配对, 连不上

            ## 蓝牙连不上

            1. 长按功能键 10 秒重置耳机。
            2. 删除手机蓝牙配对记录后重新配对。
            """;

    private static final String DEMO_POLICY_CONTENT = """
            # 退换货政策

            关键词：退货, 退款, 七天

            ## 退货期限

            耳机类商品七天无理由退货。
            """;

    private static final String ITEM_CONTENT = """
            # 森海塞尔MOMENTUM二代头戴耳机

            > 闲鱼商品快照自动生成（2026-09-04）。

            关键词：森海塞尔MOMENTUM二代头戴耳机、1079979582985、耳机、蓝牙

            ## 基本信息

            - 商品 ID：1079979582985
            - 价格：¥265.0
            - 库存：1

            ## 商品描述

            森海塞尔MOMENTUM 2代头戴式蓝牙耳机，支持蓝牙和有线连接。功能未测试，耳罩表面可见使用痕迹。
            """;

    @Test
    void xianyuTenantMustNotRecallDemoTroubleshootingDocs() {
        LocalKnowledgeSearchCapabilityProvider provider = provider(
                demoTroubleshootingDoc(),
                demoPolicyDoc(),
                xianyuItemDoc()
        );

        // 压测 #3 原句：演示排查文档以“连不上/耳机”高分命中。隔离后 xianyu 租户强制
        // productcategory=xianyu，演示文档品类（headphones）不匹配被过滤。
        CapabilityResult<?> troubleshooting = provider.invoke(
                new CapabilityCall(
                        CapabilityIds.KNOWLEDGE_SEARCH,
                        Map.of("query", "耳机跟我手机连不上蓝牙怎么办", "knowledge_type", "TROUBLESHOOTING")
                ),
                xianyuContext()
        );
        List<KnowledgeSearchHit> tsHits = hits(troubleshooting);
        assertTrue(tsHits.stream().noneMatch(hit -> hit.getDocumentId().startsWith("troubleshooting/")),
                "xianyu 租户不得召回演示排查文档");
        assertTrue(tsHits.stream().noneMatch(hit -> "蓝牙耳机 Pro Max".equals(hit.getTitle())),
                "演示商品手册不得出现在结果里");

        // 政策问句同样不得漏进演示政策文档。
        CapabilityResult<?> policy = provider.invoke(
                new CapabilityCall(CapabilityIds.KNOWLEDGE_SEARCH, Map.of("query", "耳机退货政策")),
                xianyuContext()
        );
        assertTrue(hits(policy).stream().noneMatch(hit -> "policy/return_policy.md".equals(hit.getDocumentId())),
                "xianyu 租户不得召回演示政策文档");
    }

    @Test
    void xianyuTenantStillRecallsXianyuItemSnapshot() {
        LocalKnowledgeSearchCapabilityProvider provider = provider(
                demoTroubleshootingDoc(),
                xianyuItemDoc()
        );

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall(CapabilityIds.KNOWLEDGE_SEARCH, Map.of("query", "这个耳机是森海塞尔的吗")),
                xianyuContext()
        );

        assertTrue(result.isSuccess());
        List<KnowledgeSearchHit> hits = hits(result);
        assertFalse(hits.isEmpty(), "自有商品快照文档应可正常召回");
        assertTrue(hits.stream().anyMatch(hit -> "product/1079979582985.md".equals(hit.getDocumentId())));
    }

    @Test
    void defaultTenantStillRecallsDemoDocs() {
        LocalKnowledgeSearchCapabilityProvider provider = provider(
                demoTroubleshootingDoc(),
                xianyuItemDoc()
        );

        CapabilityResult<?> result = provider.invoke(
                new CapabilityCall(
                        CapabilityIds.KNOWLEDGE_SEARCH,
                        Map.of("query", "耳机跟我手机连不上蓝牙怎么办", "knowledge_type", "TROUBLESHOOTING")
                ),
                tenantContext("default")
        );

        assertTrue(result.isSuccess());
        List<KnowledgeSearchHit> hits = hits(result);
        assertTrue(hits.stream().anyMatch(hit -> "troubleshooting/headphones_pro_max.md".equals(hit.getDocumentId())),
                "演示租户保留原检索行为");
        assertTrue(hits.stream().noneMatch(hit -> "product/1079979582985.md".equals(hit.getDocumentId())),
                "演示租户不应看到闲鱼商品快照");
    }

    @SuppressWarnings("unchecked")
    private List<KnowledgeSearchHit> hits(CapabilityResult<?> result) {
        return (List<KnowledgeSearchHit>) result.getData();
    }

    private LocalKnowledgeSearchCapabilityProvider provider(KnowledgeDocument... documents) {
        return new LocalKnowledgeSearchCapabilityProvider(
                new KnowledgeTools(List.of(documents)),
                new XianyuSidecarProperties()
        );
    }

    private KnowledgeDocument demoTroubleshootingDoc() {
        return new KnowledgeDocument(
                "troubleshooting/headphones_pro_max.md",
                "troubleshooting",
                "蓝牙耳机 Pro Max 使用与故障排查",
                DEMO_TROUBLESHOOTING_CONTENT,
                List.of("耳机", "蓝牙", "连不上", "重置", "配对"),
                KnowledgeType.TROUBLESHOOTING,
                "headphones",
                "Pro Max",
                "蓝牙耳机 Pro Max 用户手册/故障排查",
                "1.2",
                null
        );
    }

    private KnowledgeDocument demoPolicyDoc() {
        return new KnowledgeDocument(
                "policy/return_policy.md",
                "policy",
                "退换货政策",
                DEMO_POLICY_CONTENT,
                List.of("退货", "七天", "耳机"),
                KnowledgeType.POLICY,
                "",
                "",
                "classpath:/knowledge/policy/return_policy.md",
                "",
                null
        );
    }

    private KnowledgeDocument xianyuItemDoc() {
        return new KnowledgeDocument(
                "product/1079979582985.md",
                "product",
                "森海塞尔MOMENTUM二代头戴耳机",
                ITEM_CONTENT,
                List.of("森海塞尔MOMENTUM二代头戴耳机", "1079979582985", "耳机", "蓝牙"),
                KnowledgeType.POLICY,
                XianyuKnowledgeProductCatalog.PRODUCT_CATEGORY,
                "",
                "xianyu-item-snapshot",
                "1",
                null
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
