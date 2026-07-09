package com.smartcs.smart_cs_agent.tools;

/**
 * 向量数据库返回的一条知识命中。
 */
public record VectorKnowledgeHit(
        String documentId,
        String category,
        String title,
        String content,
        double score
) {
}
