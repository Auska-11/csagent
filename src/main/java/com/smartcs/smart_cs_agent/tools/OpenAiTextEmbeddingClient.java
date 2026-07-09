package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible embedding 客户端。
 *
 * 只要服务兼容 OpenAI 的 `/embeddings` 接口，就可以复用这个客户端。
 * 例如 OpenAI、Azure OpenAI，或一些本地模型网关。
 */
@Component
public class OpenAiTextEmbeddingClient implements TextEmbeddingClient {

    private final RagProperties properties;
    private final RestClient restClient;

    @Autowired
    public OpenAiTextEmbeddingClient(RagProperties properties) {
        this(properties, RestClient.builder());
    }

    OpenAiTextEmbeddingClient(RagProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(normalizeBaseUrl(properties.getEmbedding().getBaseUrl()))
                .build();
    }

    @Override
    public List<Double> embed(String text) {
        try {
            EmbeddingResponse response = restClient.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + properties.getEmbedding().getApiKey())
                    .body(toRequestBody(text))
                    .retrieve()
                    .body(EmbeddingResponse.class);

            if (response == null || response.data() == null || response.data().isEmpty()) {
                throw new IllegalStateException("Embedding response is empty");
            }

            List<Double> embedding = response.data().get(0).embedding();
            if (embedding == null || embedding.isEmpty()) {
                throw new IllegalStateException("Embedding vector is empty");
            }
            return embedding;
        } catch (RestClientException e) {
            throw new IllegalStateException("调用 embedding 服务失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> toRequestBody(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getEmbedding().getModelName());
        body.put("input", text == null ? "" : text);
        return body;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return baseUrl.replaceAll("/+$", "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingResponse(List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(List<Double> embedding) {
    }
}
