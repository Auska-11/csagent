package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeCandidate;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchDiagnostic;
import com.smartcs.smart_cs_agent.knowledge.model.VectorRetrievalOutcome;
import com.smartcs.smart_cs_agent.tools.KnowledgeVectorFilter;
import com.smartcs.smart_cs_agent.tools.KnowledgeVectorStore;
import com.smartcs.smart_cs_agent.tools.RagProperties;
import com.smartcs.smart_cs_agent.tools.TextEmbeddingClient;
import com.smartcs.smart_cs_agent.tools.VectorKnowledgeHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Embedding + Qdrant 召回通道；异常只关闭本通道，由混合策略继续使用关键词结果。 */
public class QdrantVectorRetriever {

    private final List<KnowledgeChunk> chunks;
    private final Map<String, KnowledgeChunk> chunksById;
    private final TextEmbeddingClient embeddingClient;
    private final KnowledgeVectorStore vectorStore;
    private final RagProperties properties;
    private volatile boolean indexReady;

    public QdrantVectorRetriever(List<KnowledgeChunk> chunks,
                                 TextEmbeddingClient embeddingClient,
                                 KnowledgeVectorStore vectorStore,
                                 RagProperties properties) {
        this.chunks = List.copyOf(chunks);
        Map<String, KnowledgeChunk> index = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            index.put(chunk.chunkId(), chunk);
        }
        this.chunksById = Map.copyOf(index);
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public VectorRetrievalOutcome retrieve(KnowledgeQueryPlan plan, int limit) {
        if (!properties.isHybridMode() || embeddingClient == null || vectorStore == null) {
            return VectorRetrievalOutcome.success(List.of());
        }

        try {
            ensureIndex();
        } catch (RuntimeException failure) {
            return degraded(plan, "index", failure);
        }

        List<Double> queryEmbedding;
        try {
            queryEmbedding = embeddingClient.embed(plan.originalQuery());
        } catch (RuntimeException failure) {
            return degraded(plan, "embed", failure);
        }

        try {
            KnowledgeVectorFilter filter = new KnowledgeVectorFilter(
                    plan.knowledgeType(),
                    plan.productCategory(),
                    plan.productModel(),
                    plan.version()
            );
            List<KnowledgeCandidate> candidates = new ArrayList<>();
            for (VectorKnowledgeHit hit : vectorStore.search(queryEmbedding, limit, filter)) {
                if (hit.score() < properties.getVectorMinScore()) {
                    continue;
                }
                KnowledgeChunk chunk = chunksById.get(hit.chunkId());
                if (chunk == null) {
                    continue;
                }
                KnowledgeCandidate candidate = new KnowledgeCandidate(chunk);
                candidate.vectorScore(hit.score());
                candidates.add(candidate);
            }
            return VectorRetrievalOutcome.success(candidates);
        } catch (RuntimeException failure) {
            return degraded(plan, "search", failure);
        }
    }

    private VectorRetrievalOutcome degraded(KnowledgeQueryPlan plan,
                                             String stage,
                                             RuntimeException failure) {
        KnowledgeSearchDiagnostic diagnostic = new KnowledgeSearchDiagnostic(
                KnowledgeSearchDiagnostic.VECTOR_RETRIEVAL_DEGRADED,
                stage,
                failure.getClass().getSimpleName(),
                safeErrorSummary(failure),
                "hybrid",
                plan.knowledgeType()
        );
        return new VectorRetrievalOutcome(List.of(), List.of(diagnostic));
    }

    private String safeErrorSummary(RuntimeException failure) {
        String message = failure.getMessage();
        String summary = message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").trim();
        return summary.length() <= 180 ? summary : summary.substring(0, 180);
    }

    private synchronized void ensureIndex() {
        if (indexReady) {
            return;
        }
        List<KnowledgeChunk> vectorChunks = chunks.stream()
                .filter(chunk -> chunk.knowledgeType()
                        == com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType.TROUBLESHOOTING)
                .toList();
        List<List<Double>> embeddings = new ArrayList<>();
        for (KnowledgeChunk chunk : vectorChunks) {
            embeddings.add(embeddingClient.embed(toEmbeddingText(chunk)));
        }
        int dimensions = embeddings.isEmpty() || embeddings.get(0).isEmpty()
                ? properties.getEmbedding().getDimensions()
                : embeddings.get(0).size();
        vectorStore.ensureCollection(dimensions);
        vectorStore.upsertChunks(vectorChunks, embeddings);
        indexReady = true;
    }

    private String toEmbeddingText(KnowledgeChunk chunk) {
        return "标题：" + chunk.title() + "\n"
                + "商品品类：" + chunk.productCategory() + "\n"
                + "商品型号：" + chunk.productModel() + "\n"
                + "关键词：" + String.join("，", chunk.keywords()) + "\n"
                + chunk.content();
    }
}
