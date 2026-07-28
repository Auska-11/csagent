package com.smartcs.smart_cs_agent.tools;

/**
 * 向量数据库返回的一条知识命中。
 */
public record VectorKnowledgeHit(
        String documentId,
        String chunkId,
        String knowledgeType,
        String category,
        String productCategory,
        String productModel,
        String title,
        String source,
        String version,
        String effectiveDate,
        String content,
        double score
) {
    public VectorKnowledgeHit(String documentId,
                              String category,
                              String title,
                              String content,
                              double score) {
        this(
                documentId,
                documentId + "#chunk-1",
                "",
                category,
                "",
                "",
                title,
                "",
                "",
                "",
                content,
                score
        );
    }
}
