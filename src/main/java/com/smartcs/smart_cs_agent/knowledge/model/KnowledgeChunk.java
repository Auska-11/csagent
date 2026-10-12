package com.smartcs.smart_cs_agent.knowledge.model;

import java.time.LocalDate;
import java.util.List;

/** 可独立检索和向量化的知识块，保留回答溯源和过滤所需的全部元数据。 */
public record KnowledgeChunk(
        String documentId,
        String chunkId,
        KnowledgeType knowledgeType,
        String category,
        String productCategory,
        String productModel,
        String title,
        String source,
        String version,
        LocalDate effectiveDate,
        String content,
        List<String> keywords,
        String channel
) {
    public KnowledgeChunk(String documentId, String chunkId, KnowledgeType knowledgeType, String category, String productCategory, String productModel, String title, String source, String version, LocalDate effectiveDate, String content, List<String> keywords) {
        this(documentId, chunkId, knowledgeType, category, productCategory, productModel, title, source, version, effectiveDate, content, keywords, "");
    }

    public KnowledgeChunk {
        channel = channel == null ? "" : channel.trim().toLowerCase(java.util.Locale.ROOT);
        documentId = safe(documentId);
        chunkId = safe(chunkId);
        knowledgeType = knowledgeType == null ? KnowledgeType.POLICY : knowledgeType;
        category = safe(category);
        productCategory = safe(productCategory);
        productModel = safe(productModel);
        title = safe(title);
        source = safe(source);
        version = safe(version);
        content = safe(content);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
