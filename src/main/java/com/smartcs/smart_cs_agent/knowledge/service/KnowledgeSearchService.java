package com.smartcs.smart_cs_agent.knowledge.service;

import com.smartcs.smart_cs_agent.knowledge.index.KnowledgeChunker;
import com.smartcs.smart_cs_agent.knowledge.index.MarkdownKnowledgeDocumentLoader;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeCandidate;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchRequest;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchResponse;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.knowledge.query.KnowledgeQueryPlanner;
import com.smartcs.smart_cs_agent.knowledge.query.QueryRewriter;
import com.smartcs.smart_cs_agent.knowledge.retrieval.KeywordRetriever;
import com.smartcs.smart_cs_agent.knowledge.retrieval.KnowledgeMetadataFilter;
import com.smartcs.smart_cs_agent.knowledge.retrieval.KnowledgeReranker;
import com.smartcs.smart_cs_agent.knowledge.retrieval.KnowledgeRetrievalStrategy;
import com.smartcs.smart_cs_agent.knowledge.retrieval.PolicyRetrievalStrategy;
import com.smartcs.smart_cs_agent.knowledge.retrieval.QdrantVectorRetriever;
import com.smartcs.smart_cs_agent.knowledge.retrieval.RelevanceGate;
import com.smartcs.smart_cs_agent.knowledge.retrieval.ResultFusion;
import com.smartcs.smart_cs_agent.knowledge.retrieval.TroubleshootingRetrievalStrategy;
import com.smartcs.smart_cs_agent.tools.KnowledgeDocument;
import com.smartcs.smart_cs_agent.tools.KnowledgeSearchResult;
import com.smartcs.smart_cs_agent.tools.KnowledgeVectorStore;
import com.smartcs.smart_cs_agent.tools.RagProperties;
import com.smartcs.smart_cs_agent.tools.TextEmbeddingClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** 知识检索应用服务：规划查询，选择业务策略，并输出统一可靠结果。 */
@Component
public class KnowledgeSearchService {

    private final KnowledgeQueryPlanner planner;
    private final KnowledgeRetrievalStrategy policyStrategy;
    private final KnowledgeRetrievalStrategy troubleshootingStrategy;

    @Autowired
    public KnowledgeSearchService(RagProperties properties,
                                  TextEmbeddingClient embeddingClient,
                                  KnowledgeVectorStore vectorStore) {
        this(
                new MarkdownKnowledgeDocumentLoader().load(),
                properties,
                embeddingClient,
                vectorStore
        );
    }

    public KnowledgeSearchService(List<KnowledgeDocument> documents,
                                  RagProperties properties,
                                  TextEmbeddingClient embeddingClient,
                                  KnowledgeVectorStore vectorStore) {
        // Current-item facts have their own exact-key store; stale listing prices must never become RAG evidence.
        List<KnowledgeChunk> chunks = new KnowledgeChunker().chunk(documents.stream()
                .filter(document -> !("xianyu".equals(document.getChannel()) && "product".equals(document.getCategory())))
                .toList());
        KnowledgeMetadataFilter metadataFilter = new KnowledgeMetadataFilter();
        KeywordRetriever keywordRetriever = new KeywordRetriever(chunks, metadataFilter);
        KnowledgeReranker reranker = new KnowledgeReranker();
        RelevanceGate gate = new RelevanceGate(properties);
        QdrantVectorRetriever vectorRetriever = new QdrantVectorRetriever(
                chunks,
                embeddingClient,
                vectorStore,
                properties
        );
        this.planner = new KnowledgeQueryPlanner(new QueryRewriter());
        this.policyStrategy = new PolicyRetrievalStrategy(keywordRetriever, reranker, gate);
        this.troubleshootingStrategy = new TroubleshootingRetrievalStrategy(
                keywordRetriever,
                vectorRetriever,
                new ResultFusion(),
                reranker,
                gate
        );
    }

    public List<KnowledgeSearchResult> search(KnowledgeSearchRequest request) {
        return searchWithDiagnostics(request).results();
    }

    public KnowledgeSearchResponse searchWithDiagnostics(KnowledgeSearchRequest request) {
        if (request.query().isBlank()) {
            return KnowledgeSearchResponse.empty();
        }
        KnowledgeQueryPlan plan = planner.plan(request);
        if (plan.terms().isEmpty()) {
            return KnowledgeSearchResponse.empty();
        }
        KnowledgeRetrievalStrategy strategy = plan.knowledgeType() == KnowledgeType.POLICY
                ? policyStrategy
                : troubleshootingStrategy;
        var outcome = strategy.retrieve(plan, request.topK());
        List<KnowledgeSearchResult> results = outcome.candidates().stream()
                .map(candidate -> toResult(candidate, plan))
                .toList();
        return new KnowledgeSearchResponse(results, outcome.diagnostics());
    }

    private KnowledgeSearchResult toResult(KnowledgeCandidate candidate, KnowledgeQueryPlan plan) {
        KnowledgeChunk chunk = candidate.chunk();
        return new KnowledgeSearchResult(
                chunk.documentId(),
                chunk.chunkId(),
                chunk.knowledgeType(),
                chunk.category(),
                chunk.productCategory(),
                chunk.productModel(),
                chunk.title(),
                chunk.source(),
                chunk.version(),
                (int) Math.round(candidate.rerankScore()),
                cleanSnippet(chunk.content()),
                candidate.matchedTerms(),
                candidate.matchedFields(),
                plan.queryVariants(),
                candidate.rerankReason(),
                candidate.vectorScore() * 100,
                candidate.channels()
        );
    }

    private String cleanSnippet(String content) {
        String compact = content
                .replaceAll("(?m)^关键词：.*$", "")
                .replaceAll("(?m)^keywords:.*$", "")
                .replace("#", "")
                .replaceAll("\\s+", " ")
                .trim();
        return compact.length() <= 520 ? compact : compact.substring(0, 520) + "...";
    }
}
