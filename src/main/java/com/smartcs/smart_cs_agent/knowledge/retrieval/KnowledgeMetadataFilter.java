package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;

import java.time.LocalDate;

/** 本地与 Qdrant 共享的元数据过滤语义。 */
public class KnowledgeMetadataFilter {

    public boolean matches(KnowledgeChunk chunk, KnowledgeQueryPlan plan) {
        if (!chunk.channel().equalsIgnoreCase(plan.channel())) return false;
        if (chunk.knowledgeType() != plan.knowledgeType()) {
            return false;
        }
        if (!plan.productCategory().isBlank()
                && !plan.productCategory().equalsIgnoreCase(chunk.productCategory())) {
            return false;
        }
        if (!plan.productModel().isBlank()
                && !chunk.productModel().isBlank()
                && !plan.productModel().equalsIgnoreCase(chunk.productModel())) {
            return false;
        }
        if (!plan.version().isBlank() && !plan.version().equalsIgnoreCase(chunk.version())) {
            return false;
        }
        return chunk.effectiveDate() == null || !chunk.effectiveDate().isAfter(LocalDate.now());
    }
}
