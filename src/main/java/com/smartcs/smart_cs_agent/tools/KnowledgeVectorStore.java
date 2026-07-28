package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;

import java.util.List;

/**
 * 知识库向量存储接口。
 *
 * 第一版实现使用 Qdrant REST API。
 * 抽出接口后，后续替换成 Milvus、pgvector 或 Elasticsearch dense vector 时，
 * KnowledgeTools 的上层逻辑可以尽量不动。
 */
public interface KnowledgeVectorStore {

    void ensureCollection(int dimensions);

    void upsertChunks(List<KnowledgeChunk> chunks, List<List<Double>> embeddings);

    List<VectorKnowledgeHit> search(List<Double> queryEmbedding,
                                    int topK,
                                    KnowledgeVectorFilter filter);
}
