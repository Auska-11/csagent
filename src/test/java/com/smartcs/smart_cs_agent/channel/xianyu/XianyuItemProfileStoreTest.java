package com.smartcs.smart_cs_agent.channel.xianyu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class XianyuItemProfileStoreTest {
    @TempDir Path directory;
    private XianyuSidecarProperties properties() {
        var properties = new XianyuSidecarProperties(); properties.setItemSnapshotDir(directory.toString()); return properties;
    }
    private XianyuItemProfileStore.ItemProfile profile(String condition) {
        return new XianyuItemProfileStore.ItemProfile("耳机", "headphones", "M2", "二手自用", condition, "仅耳机",
                new XianyuItemProfileStore.SellerPolicy(true, new BigDecimal("240.50"), null, "", "48 小时内发货"));
    }

    @Test void draftPublicationAndLaterEditsPreserveReviewedFactsAcrossRestart() {
        var store = new XianyuItemProfileStore(properties());
        assertEquals(1, store.saveDraft("123", 0, profile("未测试")).version());
        assertTrue(store.published("123").isEmpty());
        assertEquals(2, store.publish("123", 1, "seller").version());
        store.saveDraft("123", 2, profile("测试正常"));
        assertEquals("未测试", store.published("123").orElseThrow().condition());
        var restarted = new XianyuItemProfileStore(properties());
        assertEquals("测试正常", restarted.find("123").orElseThrow().draft().condition());
        assertEquals("未测试", restarted.published("123").orElseThrow().condition());
        assertEquals("seller", restarted.find("123").orElseThrow().reviewedBy());
        assertThrows(XianyuProfileConflictException.class, () -> restarted.publish("123", 2, "other"));
        restarted.publish("123", 3, "other");
        assertEquals("测试正常", restarted.published("123").orElseThrow().condition());
    }

    @Test void publishingProseAndAuthorizationCannotRollBackLiveSaleState() {
        var store = new XianyuItemProfileStore(properties());
        var facts = new XianyuItemFactService(new XianyuReviewedItemCatalog(List.of()), store, properties());
        facts.applyLiveOverride("123", XianyuItemFactService.PriceRange.of(new BigDecimal("265.50")), 0);
        store.saveDraft("123", 0, profile("未测试")); store.publish("123", 1, "seller");
        var item = facts.itemFacts("123").orElseThrow();
        assertEquals("¥265.5", item.price().display()); assertEquals(0, item.stock());
        assertEquals("未测试", item.condition()); assertEquals("M2", item.productModel());
        assertFalse(item.toString().contains("240.50"), "Internal authorization must not enter LLM item facts");
        assertEquals(new BigDecimal("240.50"), new XianyuSellerRuleService(new XianyuScenarioProperties(), store).forItem("123").getFloorPrice());
    }

    @Test void diskFailureCannotBeAcknowledgedAsARecordedLiveFactOrDraft() throws Exception {
        Files.writeString(directory.resolve("profiles"), "not a directory");
        var store = new XianyuItemProfileStore(properties());
        assertThrows(IllegalStateException.class, () -> store.saveDraft("123", 0, profile("未测试")));
        assertTrue(store.find("123").isEmpty());
        Files.writeString(directory.resolve("overrides"), "not a directory");
        var facts = new XianyuItemFactService(new XianyuReviewedItemCatalog(List.of()), store, properties());
        assertThrows(IllegalStateException.class, () -> facts.applyLiveOverride("123", null, 0));
        assertTrue(facts.liveOverride("123").isEmpty());
    }

    @Test void corruptSoldStateCannotSilentlyFallBackToOldCatalogStock() throws Exception {
        Files.createDirectories(directory.resolve("overrides"));
        Path file = directory.resolve("overrides/123.json");
        for (String invalid : List.of("{", "{}", "{\"itemId\":\"456\",\"stock\":0}",
                "{\"itemId\":\"123\",\"stock\":-1}", "{\"itemId\":\"123\",\"stock\":0.5}")) {
            Files.writeString(file, invalid);
            assertThrows(IllegalStateException.class, () -> new XianyuItemFactService(
                    new XianyuReviewedItemCatalog(List.of()), new XianyuItemProfileStore(properties()), properties()));
        }
    }
}
