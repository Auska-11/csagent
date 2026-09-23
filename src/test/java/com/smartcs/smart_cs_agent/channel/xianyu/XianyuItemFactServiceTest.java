package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.ItemFacts;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.PriceRange;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchRequest;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchResponse;
import com.smartcs.smart_cs_agent.knowledge.service.KnowledgeSearchService;
import com.smartcs.smart_cs_agent.tools.KnowledgeSearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * XianyuItemFactService 单元测试：审阅缺陷 #4/#7 的直接回归层。
 *
 * <p>上层 Runtime 测试（XianyuBargainRuntimeIntegrationTest）验证“事实如何影响议价决策”，
 * 这里验证“事实本身是否被忠实还原”：</p>
 * <ul>
 *     <li>否定描述保留整句原文——“没有维修过，没有划痕，不是全新”绝不能被压成
 *         “维修过；划痕；全新”（缺陷 #4：按标记词拼接等于凭空捏造缺陷）。</li>
 *     <li>价格/库存解析绝不编造：未知价格不折 0，缺库存数字是“未知”而不是“无货”。</li>
 *     <li>热更新覆盖层（缺陷 #7：改价/售出对齐现势）：部分推送合并、覆盖优先于文档、
 *         文档未入库时按覆盖值回答价格库存但标题成色保持未知。</li>
 *     <li>覆盖层持久化（评审缺陷 #5：热更新重启回退）：落盘原子写 + 重启恢复、
 *         注册快照（seed）不得覆盖更新的现势覆盖值。</li>
 * </ul>
 */
class XianyuItemFactServiceTest {

    private static final String ITEM_ID = "1079979582985";
    private static final String DOC_ID = "product/1079979582985.md";

    /** 每个测试独立快照目录：覆盖层会真实落盘，绝不能写进仓库 data/ 互相污染。 */
    @TempDir
    Path snapshotDir;

    // --- 否定描述（缺陷 #4） ---

    @Test
    void negativeConditionSentencesArePreservedVerbatimNotWordConcatenated() {
        XianyuItemFactService factService = service(knowledgeServiceWith(
                hit(DOC_ID, "MacBook Pro 14 / 商品描述", "没有维修过，没有划痕，不是全新")));

        String condition = factService.itemFacts(ITEM_ID).orElseThrow().condition();

        // 整句保留：否定词与标记词同句存活，而不是标记词拼接反转语义。
        assertEquals("没有维修过；没有划痕；不是全新", condition);
    }

    @Test
    void conditionKeepsWholeSentencesFromMixedRealisticDoc() {
        XianyuItemFactService factService = service(knowledgeServiceWith(
                hit(DOC_ID, "iPhone 13 / 商品描述",
                        "iPhone 13：自用一年，95新。没有维修过，没有划痕，不是全新。"
                                + "电池健康 91%，屏幕有轻微使用痕迹。支持蓝牙连接，功能一切正常。")));

        String condition = factService.itemFacts(ITEM_ID).orElseThrow().condition();

        assertTrue(condition.contains("95新"));
        assertTrue(condition.contains("没有维修过"));
        assertTrue(condition.contains("没有划痕"));
        assertTrue(condition.contains("不是全新"));
        assertTrue(condition.contains("电池健康 91%"));
        assertTrue(condition.contains("屏幕有轻微使用痕迹"));
        // “支持蓝牙连接”是型号能力（无成色标记词），不得混入这一件的实际状态。
        assertFalse(condition.contains("支持蓝牙连接"));
        // 旧缺陷形态（裸标记词拼接）不得再出现。
        assertFalse(condition.contains("划痕；全新"));
        assertFalse(condition.contains("全新；维修过"));
    }

    // --- 价格解析：未知绝不折 0 ---

    @Test
    void priceIsParsedAsRangeAndUnknownNeverCollapsesToZero() {
        assertEquals("¥265", priceFromDocContent("价格：¥265").display());
        assertTrue(priceFromDocContent("价格：¥265").singleValue());
        assertEquals("¥240 - ¥280", priceFromDocContent("价格：¥240 - ¥280").display());
        assertEquals("¥240 - ¥280", priceFromDocContent("价格：¥280到240").display());

        // “以商品页为准”是未知，不是 0 元。
        PriceRange unknown = priceFromDocContent("价格：以商品页为准");
        assertFalse(unknown.known());
        assertEquals("", unknown.display());
    }

    // --- 库存解析：缺失是未知，不是无货 ---

    @Test
    void missingStockMeansUnknownNotSoldOut() {
        ItemFacts inStock = factsFromDocContent("库存：1");
        assertEquals(1, inStock.stock());
        assertTrue(inStock.stockKnown());
        assertTrue(inStock.inStock());

        ItemFacts unknown = factsFromDocContent("仅描述商品，未提及库存");
        assertEquals(-1, unknown.stock());
        assertFalse(unknown.stockKnown());
        assertFalse(unknown.inStock());

        ItemFacts soldOut = factsFromDocContent("库存：0");
        assertEquals(0, soldOut.stock());
        assertTrue(soldOut.stockKnown());
        assertFalse(soldOut.inStock());
    }

    // --- 文档过滤与查询语义 ---

    @Test
    void itemFactsFiltersToExactDocumentAndQueriesByItemId() {
        KnowledgeSearchService knowledgeService = knowledgeServiceWith(
                hit(DOC_ID, "MacBook Pro 14 / 基本信息", "价格：¥265\n库存：1\n商品 ID：1079979582985"),
                hit(DOC_ID, "MacBook Pro 14 / 商品描述", "MacBook Pro 14：自用一年，没有维修过，没有划痕"),
                hit("product/999999.md", "其他商品 / 基本信息", "价格：¥9999\n库存：88"));
        XianyuItemFactService factService = service(knowledgeService);

        ItemFacts facts = factService.itemFacts(ITEM_ID).orElseThrow();

        // 其他商品的命中（¥9999/库存 88）不得混入当前商品事实。
        assertEquals("MacBook Pro 14", facts.title());
        assertTrue(facts.price().singleValue());
        assertEquals(0, new BigDecimal("265").compareTo(facts.price().min()));
        assertEquals(1, facts.stock());
        assertEquals("自用一年，没有维修过，没有划痕", facts.description());
        assertEquals("没有维修过；没有划痕", facts.condition());

        // 检索必须按 itemId 查询（当前商品是确定的，不做语义猜测）。
        ArgumentCaptor<KnowledgeSearchRequest> captor = ArgumentCaptor.forClass(KnowledgeSearchRequest.class);
        verify(knowledgeService).searchWithDiagnostics(captor.capture());
        assertEquals(ITEM_ID, captor.getValue().query());
    }

    @Test
    void missingDocWithoutOverrideReturnsEmptyAndNeverFabricates() {
        KnowledgeSearchService knowledgeService = emptyKnowledgeService();
        XianyuItemFactService factService = service(knowledgeService);

        assertTrue(factService.itemFacts(ITEM_ID).isEmpty());
        assertTrue(factService.currentItemFacts("xianyu_buyer-01_1079979582985").isEmpty());

        // 非法 itemId 直接拒绝，不打扰知识检索（用独立 mock 隔离前两步的合法查询）。
        KnowledgeSearchService untouched = emptyKnowledgeService();
        XianyuItemFactService guardOnly = service(untouched);
        assertTrue(guardOnly.itemFacts(null).isEmpty());
        assertTrue(guardOnly.itemFacts("   ").isEmpty());
        verifyNoInteractions(untouched);
    }

    // --- 热更新覆盖层（缺陷 #7：改价/售出对齐现势） ---

    @Test
    void liveOverrideMergesPartialPushesAndWinsOverDocument() {
        XianyuItemFactService factService = service(knowledgeServiceWith(
                hit(DOC_ID, "MacBook Pro 14 / 基本信息", "价格：¥265\n库存：1")));

        // 改价只推价格：库存仍以文档为准。
        factService.applyLiveOverride(ITEM_ID, PriceRange.of(new BigDecimal("245")), null);
        ItemFacts repriced = factService.itemFacts(ITEM_ID).orElseThrow();
        assertEquals(0, new BigDecimal("245").compareTo(repriced.price().min()));
        assertEquals(1, repriced.stock());
        assertTrue(repriced.inStock());

        // 售出只推库存：价格沿用上一次覆盖值，而不是回落到文档旧价。
        factService.applyLiveOverride(ITEM_ID, null, 0);
        ItemFacts soldOut = factService.itemFacts(ITEM_ID).orElseThrow();
        assertEquals(0, new BigDecimal("245").compareTo(soldOut.price().min()));
        assertEquals(0, soldOut.stock());
        assertFalse(soldOut.inStock());
        assertTrue(soldOut.stockKnown());

        // 覆盖层可查询且保持合并结果。
        Optional<XianyuItemFactService.ItemFactOverride> merged = factService.liveOverride(ITEM_ID);
        assertTrue(merged.isPresent());
        assertEquals(0, new BigDecimal("245").compareTo(merged.get().price().min()));
        assertEquals(0, merged.get().stock());

        // 空推送（两字段都为 null）与空 itemId 被忽略。
        factService.applyLiveOverride(ITEM_ID, null, null);
        factService.applyLiveOverride("  ", PriceRange.of(new BigDecimal("1")), 3);
        assertTrue(factService.liveOverride("never-pushed-item").isEmpty());
    }

    @Test
    void overrideWithoutDocAnswersPriceAndStockButNeverInventsTitleOrCondition() {
        XianyuItemFactService factService = service(emptyKnowledgeService());
        factService.applyLiveOverride(ITEM_ID, PriceRange.of(new BigDecimal("250")), 1);

        // 文档还在人工审校：价格库存按覆盖值回答，标题/描述/成色保持未知，绝不编造。
        ItemFacts facts = factService.itemFacts(ITEM_ID).orElseThrow();
        assertEquals(ITEM_ID, facts.itemId());
        assertEquals("", facts.title());
        assertEquals("", facts.description());
        assertEquals("", facts.condition());
        assertEquals("¥250", facts.price().display());
        assertEquals(1, facts.stock());
        assertTrue(facts.inStock());
    }

    // --- 会话身份与租户 ---

    @Test
    void currentItemIdParsesOnlyXianyuConversationShape() {
        XianyuItemFactService factService = service(emptyKnowledgeService());

        assertEquals(Optional.of(ITEM_ID), factService.currentItemId("xianyu_buyer-01_" + ITEM_ID));
        assertEquals(Optional.empty(), factService.currentItemId("conv-123"));
        assertEquals(Optional.empty(), factService.currentItemId("xianyu_only"));
        assertEquals(Optional.empty(), factService.currentItemId(null));
    }

    @Test
    void isXianyuConversationRequiresTenantAndConversationShape() {
        XianyuItemFactService factService = service(emptyKnowledgeService());

        assertTrue(factService.isXianyuConversation("xianyu", "xianyu_b_" + ITEM_ID));
        assertTrue(factService.isXianyuConversation(" Xianyu ", "xianyu_b_" + ITEM_ID));
        assertFalse(factService.isXianyuConversation("mall", "xianyu_b_" + ITEM_ID));
        assertFalse(factService.isXianyuConversation("xianyu", "conv-1"));
        assertFalse(factService.isXianyuConversation(null, "xianyu_b_" + ITEM_ID));
    }

    // --- 侧车裸价格文本解析 ---

    @Test
    void parsePriceRangeTextAcceptsSidecarFormsAndRejectsGarbage() {
        assertEquals(Optional.of(range("265", "265")), XianyuItemFactService.parsePriceRangeText("265"));
        assertEquals(Optional.of(range("265", "280")), XianyuItemFactService.parsePriceRangeText("¥265-280"));
        assertEquals(Optional.of(range("265", "280")), XianyuItemFactService.parsePriceRangeText("265~280"));
        assertEquals(Optional.of(range("265", "280")), XianyuItemFactService.parsePriceRangeText("280到265"));
        assertEquals(Optional.of(range("39.9", "59")), XianyuItemFactService.parsePriceRangeText("39.9-59"));

        // 面议/0/空白/非法输入一律拒绝，绝不猜价。
        assertEquals(Optional.empty(), XianyuItemFactService.parsePriceRangeText("面议"));
        assertEquals(Optional.empty(), XianyuItemFactService.parsePriceRangeText("0"));
        assertEquals(Optional.empty(), XianyuItemFactService.parsePriceRangeText(""));
        assertEquals(Optional.empty(), XianyuItemFactService.parsePriceRangeText(null));
    }

    // --- 覆盖层持久化（评审缺陷 #5：热更新重启回退） ---

    @Test
    void liveOverridesSurviveRestartInsteadOfFallingBackToDocument() {
        // 文档写的是旧事实：¥265 / 库存 1。
        KnowledgeSearchService knowledgeService = knowledgeServiceWith(
                hit(DOC_ID, "MacBook Pro 14 / 基本信息", "价格：¥265\n库存：1"));
        XianyuItemFactService before = service(knowledgeService);
        before.applyLiveOverride(ITEM_ID, PriceRange.of(new BigDecimal("245")), null);
        before.applyLiveOverride(ITEM_ID, null, 0);

        // 服务重启：同一快照目录上的新实例从磁盘恢复覆盖层，售出状态不回退为文档的库存 1。
        XianyuItemFactService after = service(knowledgeServiceWith(
                hit(DOC_ID, "MacBook Pro 14 / 基本信息", "价格：¥265\n库存：1")));
        ItemFacts facts = after.itemFacts(ITEM_ID).orElseThrow();
        assertEquals(0, new BigDecimal("245").compareTo(facts.price().min()));
        assertEquals(0, facts.stock());
        assertFalse(facts.inStock());
    }

    @Test
    void seededSnapshotNeverOverwritesNewerLiveOverride() {
        XianyuItemFactService factService = service(emptyKnowledgeService());

        // 侧车 TTL 过期重拉推送了售出事实。
        factService.applyLiveOverride(ITEM_ID, null, 0);

        // 会话注册又带来一份旧快照（库存 1）：seed 必须让位，绝不能把售出改回有货。
        assertFalse(factService.seedLiveOverride(ITEM_ID, PriceRange.of(new BigDecimal("265")), 1));
        ItemFacts facts = factService.itemFacts(ITEM_ID).orElseThrow();
        assertEquals(0, facts.stock());

        // 全新商品没有覆盖值时，注册快照正常生效并落盘。
        assertTrue(factService.seedLiveOverride("2099", PriceRange.of(new BigDecimal("88")), 3));
        ItemFacts seeded = factService.itemFacts("2099").orElseThrow();
        assertEquals(0, new BigDecimal("88").compareTo(seeded.price().min()));
        assertEquals(3, seeded.stock());

        // 空推送与空 itemId 与 apply 同样收敛。
        assertFalse(factService.seedLiveOverride("2099", null, null));
        assertFalse(factService.seedLiveOverride("  ", PriceRange.of(new BigDecimal("1")), 1));
    }

    @Test
    void seededOverrideIsPersistedAndSurvivesRestart() {
        XianyuItemFactService before = service(emptyKnowledgeService());
        assertTrue(before.seedLiveOverride(ITEM_ID, PriceRange.of(new BigDecimal("265")), 1));

        XianyuItemFactService after = service(emptyKnowledgeService());
        ItemFacts facts = after.itemFacts(ITEM_ID).orElseThrow();
        assertEquals("¥265", facts.price().display());
        assertEquals(1, facts.stock());
    }

    // --- 构造辅助 ---

    private static KnowledgeSearchService knowledgeServiceWith(KnowledgeSearchResult... hits) {
        KnowledgeSearchService service = mock(KnowledgeSearchService.class);
        when(service.searchWithDiagnostics(any(KnowledgeSearchRequest.class)))
                .thenReturn(new KnowledgeSearchResponse(List.of(hits), List.of()));
        return service;
    }

    private static KnowledgeSearchService emptyKnowledgeService() {
        KnowledgeSearchService service = mock(KnowledgeSearchService.class);
        when(service.searchWithDiagnostics(any(KnowledgeSearchRequest.class)))
                .thenReturn(KnowledgeSearchResponse.empty());
        return service;
    }

    private static KnowledgeSearchResult hit(String documentId, String title, String snippet) {
        return new KnowledgeSearchResult(documentId, "product", title, 100, snippet);
    }

    private XianyuItemFactService service(KnowledgeSearchService knowledgeService) {
        XianyuSidecarProperties properties = new XianyuSidecarProperties();
        properties.setItemSnapshotDir(snapshotDir.toString());
        return new XianyuItemFactService(knowledgeService, properties);
    }

    private ItemFacts factsFromDocContent(String content) {
        XianyuItemFactService factService = service(knowledgeServiceWith(
                hit(DOC_ID, "MacBook Pro 14 / 基本信息", content)));
        return factService.itemFacts(ITEM_ID).orElseThrow();
    }

    private PriceRange priceFromDocContent(String content) {
        return factsFromDocContent(content).price();
    }

    private static PriceRange range(String min, String max) {
        return new PriceRange(new BigDecimal(min), new BigDecimal(max));
    }
}
