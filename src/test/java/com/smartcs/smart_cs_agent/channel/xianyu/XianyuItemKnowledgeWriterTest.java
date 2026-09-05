package com.smartcs.smart_cs_agent.channel.xianyu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品知识草稿落盘的"标题前缀去重"（客服Agent-76 §6.2 附带 nit）：
 * 闲鱼 desc 常以商品标题开头，目录展示时外层又打印名称，落盘前必须剥掉重复前缀。
 */
class XianyuItemKnowledgeWriterTest {

    @TempDir
    Path tempDir;

    private final XianyuItemKnowledgeWriter writer = new XianyuItemKnowledgeWriter();

    @Test
    void draftShouldStripTitlePrefixFromDescription() throws Exception {
        String snapshot = """
                {"title":"森海塞尔MOMENTUM二代头戴耳机",\
                "desc":"森海塞尔MOMENTUM二代头戴耳机，森海塞尔MOMENTUM 2代头戴式蓝牙耳机，支持蓝牙和有线连接。",\
                "price_range":"¥265.0","total_stock":"1"}
                """;

        String status = writer.writeDraft("1079979582985", snapshot, tempDir);

        assertEquals(XianyuItemKnowledgeWriter.STATUS_CREATED, status);
        String markdown = Files.readString(tempDir.resolve("1079979582985.md"));
        assertTrue(markdown.contains("## 商品描述"));
        assertTrue(markdown.contains("森海塞尔MOMENTUM 2代头戴式蓝牙耳机"),
                "描述正文应保留：" + markdown);
        assertTrue(!markdown.contains("商品描述\n\n森海塞尔MOMENTUM二代头戴耳机，"),
                "描述不得再以标题开头逐字重复：" + markdown);
    }

    @Test
    void descriptionWithoutTitlePrefixStaysVerbatim() throws Exception {
        String snapshot = """
                {"title":"蓝牙耳机 Pro Max","desc":"支持主动降噪，续航30小时。",\
                "price_range":"¥39 - ¥59","total_stock":"8"}
                """;

        writer.writeDraft("987654321", snapshot, tempDir);

        String markdown = Files.readString(tempDir.resolve("987654321.md"));
        assertTrue(markdown.contains("## 商品描述\n\n支持主动降噪，续航30小时。"));
    }

    @Test
    void blankOrInvalidSnapshotShouldSkipWithoutWritingDraft() {
        assertEquals(XianyuItemKnowledgeWriter.STATUS_SKIPPED,
                writer.writeDraft("1", "", tempDir));
        assertEquals(XianyuItemKnowledgeWriter.STATUS_SKIPPED,
                writer.writeDraft("2", "not-json", tempDir));
        assertTrue(!Files.exists(tempDir.resolve("2.md")));
    }
}
