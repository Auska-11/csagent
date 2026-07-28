package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeCandidate;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeRetrievalOutcome;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.knowledge.model.VectorRetrievalOutcome;

import java.util.List;

/** 故障知识使用关键词与 Qdrant 独立召回、RRF 去重融合、规则重排和统一阈值。 */
public class TroubleshootingRetrievalStrategy implements KnowledgeRetrievalStrategy {

    private final KeywordRetriever keywordRetriever;
    private final QdrantVectorRetriever vectorRetriever;
    private final ResultFusion resultFusion;
    private final KnowledgeReranker reranker;
    private final RelevanceGate relevanceGate;

    public TroubleshootingRetrievalStrategy(KeywordRetriever keywordRetriever,
                                            QdrantVectorRetriever vectorRetriever,
                                            ResultFusion resultFusion,
                                            KnowledgeReranker reranker,
                                            RelevanceGate relevanceGate) {
        this.keywordRetriever = keywordRetriever;
        this.vectorRetriever = vectorRetriever;
        this.resultFusion = resultFusion;
        this.reranker = reranker;
        this.relevanceGate = relevanceGate;
    }

    @Override
    public KnowledgeRetrievalOutcome retrieve(KnowledgeQueryPlan plan, int topK) {
        int recallLimit = Math.max(topK * 4, 12);
        List<KnowledgeCandidate> keyword = keywordRetriever.retrieve(plan, recallLimit);
        VectorRetrievalOutcome vector = vectorRetriever.retrieve(plan, recallLimit);
        List<KnowledgeCandidate> fused = resultFusion.fuse(keyword, vector.candidates());
        List<KnowledgeCandidate> reranked = reranker.rerank(plan, fused);
        return new KnowledgeRetrievalOutcome(
                relevanceGate.reliable(KnowledgeType.TROUBLESHOOTING, reranked, topK),
                vector.diagnostics()
        );
    }
}
