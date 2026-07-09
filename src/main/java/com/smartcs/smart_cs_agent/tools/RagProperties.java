package com.smartcs.smart_cs_agent.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索配置。
 *
 * 【为什么默认还是 local？】
 * 真 embedding + 向量数据库需要外部模型和 Qdrant 服务。
 * 如果默认启用，会让本地学习、单元测试和 CI 都变复杂。
 *
 * 所以第一版采用双模式：
 * - local：默认模式，继续使用 Markdown + 关键词 + 本地轻量向量分。
 * - embedding-qdrant：高级模式，调用 OpenAI-compatible embedding API，并从 Qdrant 检索。
 */
@Component
@ConfigurationProperties(prefix = "smartcs.rag")
public class RagProperties {

    /** local 或 embedding-qdrant */
    private String mode = "local";

    private Embedding embedding = new Embedding();

    private Qdrant qdrant = new Qdrant();

    public boolean isEmbeddingQdrantMode() {
        return "embedding-qdrant".equalsIgnoreCase(mode);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public void setEmbedding(Embedding embedding) {
        this.embedding = embedding;
    }

    public Qdrant getQdrant() {
        return qdrant;
    }

    public void setQdrant(Qdrant qdrant) {
        this.qdrant = qdrant;
    }

    public static class Embedding {

        /** OpenAI-compatible baseUrl，例如 https://api.openai.com/v1 */
        private String baseUrl = "https://api.openai.com/v1";

        /** 不要写死真实 key，通过环境变量传入 */
        private String apiKey = "dummy-test-key";

        /** 常见维度是 1536，具体以所选 embedding 模型为准 */
        private String modelName = "text-embedding-3-small";

        private int dimensions = 1536;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }
    }

    public static class Qdrant {

        /** 本地默认端口；Docker Compose 中会覆盖成 http://qdrant:6333 */
        private String baseUrl = "http://localhost:6333";

        private String collectionName = "smart_cs_knowledge";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getCollectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }
    }
}
