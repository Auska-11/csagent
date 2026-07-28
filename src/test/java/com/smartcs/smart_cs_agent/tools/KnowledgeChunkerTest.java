package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.knowledge.index.KnowledgeChunker;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeChunkerTest {

    @Test
    void shouldSplitMarkdownByHeadingAndPreserveMetadata() {
        KnowledgeDocument document = new KnowledgeDocument(
                "troubleshooting/headphones.md",
                "troubleshooting",
                "耳机手册",
                """
                        # 耳机手册
                        ## 单侧无声
                        关键词：一边响, 单侧无声
                        请重新配对左右耳。
                        ## 无法配对
                        关键词：连不上, 配对失败
                        请清除旧的蓝牙记录。
                        """,
                List.of("耳机"),
                KnowledgeType.TROUBLESHOOTING,
                "headphones",
                "Pro Max",
                "耳机手册/故障排查",
                "1.2",
                null
        );

        List<KnowledgeChunk> chunks = new KnowledgeChunker().chunk(document);

        assertEquals(2, chunks.size());
        assertFalse(chunks.get(0).chunkId().equals(chunks.get(1).chunkId()));
        assertTrue(chunks.get(0).title().contains("单侧无声"));
        assertTrue(chunks.get(0).content().contains("重新配对"));
        assertFalse(chunks.get(0).content().contains("清除旧的蓝牙记录"));
        assertEquals("headphones", chunks.get(0).productCategory());
        assertEquals("Pro Max", chunks.get(0).productModel());
        assertEquals("耳机手册/故障排查", chunks.get(0).source());
    }
}
