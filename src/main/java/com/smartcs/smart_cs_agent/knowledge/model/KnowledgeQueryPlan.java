package com.smartcs.smart_cs_agent.knowledge.model;

import java.util.List;

/** Planner 输出：确定业务类型、检索过滤条件和规则改写结果。 */
public record KnowledgeQueryPlan(
        String originalQuery,
        KnowledgeType knowledgeType,
        String productCategory,
        String productModel,
        String version,
        List<String> queryVariants,
        List<String> terms,
        String channel
) {
    public KnowledgeQueryPlan(String originalQuery, KnowledgeType knowledgeType, String productCategory, String productModel, String version, List<String> queryVariants, List<String> terms) {
        this(originalQuery, knowledgeType, productCategory, productModel, version, queryVariants, terms, "");
    }

    public KnowledgeQueryPlan {
        channel = channel == null ? "" : channel.trim().toLowerCase(java.util.Locale.ROOT);
        queryVariants = queryVariants == null ? List.of() : List.copyOf(queryVariants);
        terms = terms == null ? List.of() : List.copyOf(terms);
    }
}
