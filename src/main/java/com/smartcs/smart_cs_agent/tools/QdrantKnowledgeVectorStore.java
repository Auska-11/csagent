package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Qdrant 向量数据库客户端。
 *
 * 【为什么用 REST 而不是 SDK？】
 * 第一版为了减少依赖和网络下载成本，直接使用 Qdrant HTTP API。
 * 后续如果项目规模变大，再替换成官方 SDK 也不影响 KnowledgeTools 的主流程。
 */
@Component
public class QdrantKnowledgeVectorStore implements KnowledgeVectorStore {

    private final RagProperties properties;
    private final RestClient restClient;

    @Autowired
    public QdrantKnowledgeVectorStore(RagProperties properties) {
        this(properties, RestClient.builder());
    }

    QdrantKnowledgeVectorStore(RagProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(normalizeBaseUrl(properties.getQdrant().getBaseUrl()))
                .build();
    }

    @Override
    public void ensureCollection(int dimensions) {
        try {
            restClient.put()
                    .uri("/collections/{collection}", properties.getQdrant().getCollectionName())
                    .body(Map.of(
                            "vectors", Map.of(
                                    "size", dimensions,
                                    "distance", "Cosine"
                            )
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("初始化 Qdrant collection 失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void upsertDocuments(List<KnowledgeDocument> documents, List<List<Double>> embeddings) {
        if (documents.size() != embeddings.size()) {
            throw new IllegalArgumentException("documents size must match embeddings size");
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            KnowledgeDocument document = documents.get(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", toStablePointId(document.getId()));
            point.put("vector", embeddings.get(i));
            point.put("payload", toPayload(document));
            points.add(point);
        }

        try {
            restClient.put()
                    .uri("/collections/{collection}/points?wait=true", properties.getQdrant().getCollectionName())
                    .body(Map.of("points", points))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("写入 Qdrant 向量失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<VectorKnowledgeHit> search(List<Double> queryEmbedding, int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", queryEmbedding);
        body.put("limit", topK);
        body.put("with_payload", true);

        try {
            SearchResponse response = restClient.post()
                    .uri("/collections/{collection}/points/search", properties.getQdrant().getCollectionName())
                    .body(body)
                    .retrieve()
                    .body(SearchResponse.class);

            if (response == null || response.result() == null) {
                return List.of();
            }

            return response.result()
                    .stream()
                    .map(this::toHit)
                    .toList();
        } catch (RestClientException e) {
            throw new IllegalStateException("查询 Qdrant 向量失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> toPayload(KnowledgeDocument document) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", document.getId());
        payload.put("category", document.getCategory());
        payload.put("title", document.getTitle());
        payload.put("content", document.getContent());
        return payload;
    }

    private VectorKnowledgeHit toHit(SearchResult result) {
        Map<String, Object> payload = result.payload() == null ? Map.of() : result.payload();
        return new VectorKnowledgeHit(
                stringPayload(payload, "documentId"),
                stringPayload(payload, "category"),
                stringPayload(payload, "title"),
                stringPayload(payload, "content"),
                result.score()
        );
    }

    private String stringPayload(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }

    private String toStablePointId(String documentId) {
        UUID uuid = UUID.nameUUIDFromBytes(documentId.getBytes(StandardCharsets.UTF_8));
        return uuid.toString();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:6333";
        }
        return baseUrl.replaceAll("/+$", "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResponse(List<SearchResult> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResult(double score, Map<String, Object> payload) {
    }
}
