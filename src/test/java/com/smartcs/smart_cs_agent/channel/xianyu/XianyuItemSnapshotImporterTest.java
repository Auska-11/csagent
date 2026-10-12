package com.smartcs.smart_cs_agent.channel.xianyu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class XianyuItemSnapshotImporterTest {
    @TempDir Path directory;
    private XianyuItemProfileStore store() {
        var properties = new XianyuSidecarProperties();
        properties.setItemSnapshotDir(directory.toString());
        return new XianyuItemProfileStore(properties);
    }
    @Test void snapshotSeedsDraftAndStripsExactTitleButCannotPublishIt() {
        var store = store();
        var importer = new XianyuItemSnapshotImporter(store);
        assertEquals("created", importer.importDraft("123", "{\"title\":\"耳机\",\"desc\":\"耳机，功能未测试\"}"));
        assertEquals("功能未测试", store.find("123").orElseThrow().draft().description());
        assertTrue(store.published("123").isEmpty());
        assertFalse(store.find("123").orElseThrow().draft().sellerPolicy().bargainAccepted());
        assertEquals("existing", importer.importDraft("123", "{\"title\":\"旧快照\",\"desc\":\"测试正常\"}"));
        assertEquals("功能未测试", store.find("123").orElseThrow().draft().description());
        assertEquals("功能未测试", store().find("123").orElseThrow().draft().description());
    }
    @Test void descriptionWithoutTitlePrefixRemainsVerbatim() {
        var store = store();
        new XianyuItemSnapshotImporter(store).importDraft("124", "{\"title\":\"耳机\",\"desc\":\"没有维修过，没有划痕\"}");
        assertEquals("没有维修过，没有划痕", store.find("124").orElseThrow().draft().description());
    }
    @Test void invalidSnapshotNeverCreatesReviewedFacts() {
        var store = store(); var importer = new XianyuItemSnapshotImporter(store);
        for (String snapshot : new String[]{"", "not-json", "null", "{}", "[]"}) assertEquals("skipped", importer.importDraft("125", snapshot));
        assertTrue(store.find("125").isEmpty());
    }
}
