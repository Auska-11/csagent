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
 * 所以采用双模式：
 * - local：默认模式，只使用本地字段加权关键词检索。
 * - hybrid：故障知识使用关键词 + Embedding/Qdrant 混合召回。
 *
 * <p>旧值 embedding-qdrant 作为 hybrid 的兼容别名保留。</p>
 */
@Component
@ConfigurationProperties(prefix = "smartcs.rag")
public class RagProperties {

    /** local 或 hybrid；embedding-qdrant 是兼容别名 */
    private String mode = "local";

    /** 政策严格阈值，使用规则重排后的 0-100 分。 */
    private double policyMinScore = 24;

    /** 故障知识统一阈值，使用规则重排后的 0-100 分。 */
    private double troubleshootingMinScore = 24;

    /** Qdrant 原始余弦相似度最低要求。 */
    private double vectorMinScore = 0.72;

    private Embedding embedding = new Embedding();

    private Qdrant qdrant = new Qdrant();

    public boolean isEmbeddingQdrantMode() {
        return isHybridMode();
    }

    public boolean isHybridMode() {
        return "hybrid".equalsIgnoreCase(mode)
                || "embedding-qdrant".equalsIgnoreCase(mode);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public double getPolicyMinScore() {
        return policyMinScore;
    }

    public void setPolicyMinScore(double policyMinScore) {
        this.policyMinScore = requireScore("smartcs.rag.policy-min-score", policyMinScore, 100);
    }

    public double getTroubleshootingMinScore() {
        return troubleshootingMinScore;
    }

    public void setTroubleshootingMinScore(double troubleshootingMinScore) {
        this.troubleshootingMinScore = requireScore(
                "smartcs.rag.troubleshooting-min-score",
                troubleshootingMinScore,
                100
        );
    }

    public double getVectorMinScore() {
        return vectorMinScore;
    }

    public void setVectorMinScore(double vectorMinScore) {
        this.vectorMinScore = requireScore("smartcs.rag.vector-min-score", vectorMinScore, 1);
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

        /** 建立 embedding HTTP 连接的上限。 */
        private int connectTimeoutMs = 3000;

        /** 等待 embedding 向量响应的上限。 */
        private int readTimeoutMs = 10000;

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

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = BoundedRestClientFactory.requirePositive(
                    "smartcs.rag.embedding.connect-timeout-ms",
                    connectTimeoutMs
            );
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = BoundedRestClientFactory.requirePositive(
                    "smartcs.rag.embedding.read-timeout-ms",
                    readTimeoutMs
            );
        }
    }

    public static class Qdrant {

        /** 本地默认端口；Docker Compose 中会覆盖成 http://qdrant:6333 */
        private String baseUrl = "http://localhost:6333";

        private String collectionName = "smart_cs_knowledge";

        /** 建立 Qdrant HTTP 连接的上限。 */
        private int connectTimeoutMs = 3000;

        /** 等待 Qdrant 建表、写入或检索响应的上限。 */
        private int readTimeoutMs = 10000;

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

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = BoundedRestClientFactory.requirePositive(
                    "smartcs.rag.qdrant.connect-timeout-ms",
                    connectTimeoutMs
            );
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = BoundedRestClientFactory.requirePositive(
                    "smartcs.rag.qdrant.read-timeout-ms",
                    readTimeoutMs
            );
        }
    }

    private double requireScore(String property, double value, double max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(property + " must be between 0 and " + max);
        }
        return value;
    }
}
