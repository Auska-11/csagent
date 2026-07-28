package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeCandidate;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeRetrievalOutcome;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;

import java.util.List;

/** 政策仅走可解释的字段加权关键词检索，并使用严格统一阈值。 */
public class PolicyRetrievalStrategy implements KnowledgeRetrievalStrategy {

    private final KeywordRetriever keywordRetriever;
    private final KnowledgeReranker reranker;
    private final RelevanceGate relevanceGate;

    public PolicyRetrievalStrategy(KeywordRetriever keywordRetriever,
                                   KnowledgeReranker reranker,
                                   RelevanceGate relevanceGate) {
        this.keywordRetriever = keywordRetriever;
        this.reranker = reranker;
        this.relevanceGate = relevanceGate;
    }

    @Override
    public KnowledgeRetrievalOutcome retrieve(KnowledgeQueryPlan plan, int topK) {
        List<KnowledgeCandidate> keyword = keywordRetriever.retrieve(plan, Math.max(topK * 4, 12));
        List<KnowledgeCandidate> reranked = reranker.rerank(plan, keyword);
        return KnowledgeRetrievalOutcome.success(
                relevanceGate.reliable(KnowledgeType.POLICY, reranked, topK)
        );
    }
}
