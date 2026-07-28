package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeRetrievalOutcome;

/** 按知识类型隔离的检索策略。 */
public interface KnowledgeRetrievalStrategy {

    KnowledgeRetrievalOutcome retrieve(KnowledgeQueryPlan plan, int topK);
}
