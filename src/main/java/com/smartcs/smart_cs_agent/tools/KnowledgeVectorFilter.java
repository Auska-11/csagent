package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;

/** 向量存储的元数据过滤条件。 */
public record KnowledgeVectorFilter(
        KnowledgeType knowledgeType,
        String productCategory,
        String productModel,
        String version
) {
    public KnowledgeVectorFilter {
        productCategory = safe(productCategory);
        productModel = safe(productModel);
        version = safe(version);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
