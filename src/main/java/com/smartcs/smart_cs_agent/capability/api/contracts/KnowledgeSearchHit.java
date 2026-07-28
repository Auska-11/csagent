package com.smartcs.smart_cs_agent.capability.api.contracts;

import java.util.Map;

/**
 * {@code knowledge.search} 的稳定输出条目。
 *
 * <p>本地知识库原先返回 tools 包中的内部对象，远程 MCP 则会把 JSON 解析成 Map。
 * 如果 Workflow 分别认识这两种形态，Provider 一切换就要改业务代码。本 DTO 是二者共同遵守的
 * Capability 输出契约，让 Workflow 永远只面对同一种 Java 类型。</p>
 *
 * <p>这里有意只暴露回答需要的字段，不暴露本地检索器内部的 rerank 细节。</p>
 */
public final class KnowledgeSearchHit {

    private final String documentId;
    private final String chunkId;
    private final String knowledgeType;
    private final String category;
    private final String productCategory;
    private final String productModel;
    private final String title;
    private final String source;
    private final String version;
    private final int score;
    private final String snippet;

    public KnowledgeSearchHit(String documentId,
                              String category,
                              String title,
                              int score,
                              String snippet) {
        this(documentId, "", "", category, "", "", title, "", "", score, snippet);
    }

    public KnowledgeSearchHit(String documentId,
                              String chunkId,
                              String knowledgeType,
                              String category,
                              String productCategory,
                              String productModel,
                              String title,
                              String source,
                              String version,
                              int score,
                              String snippet) {
        this.documentId = safeText(documentId);
        this.chunkId = safeText(chunkId);
        this.knowledgeType = safeText(knowledgeType);
        this.category = safeText(category);
        this.productCategory = safeText(productCategory);
        this.productModel = safeText(productModel);
        this.title = safeText(title);
        this.source = safeText(source);
        this.version = safeText(version);
        this.score = score;
        this.snippet = safeText(snippet);
    }

    /**
     * 将远程 MCP JSON 反序列化得到的 Map 转回稳定契约。
     * 缺少非关键字段时使用安全默认值；不是 Map 时返回 null，由调用方决定降级。
     */
    public static KnowledgeSearchHit fromMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new KnowledgeSearchHit(
                text(map, "documentId", "document_id"),
                text(map, "chunkId", "chunk_id"),
                text(map, "knowledgeType", "knowledge_type"),
                text(map, "category"),
                text(map, "productCategory", "product_category"),
                text(map, "productModel", "product_model"),
                text(map, "title"),
                text(map, "source"),
                text(map, "version"),
                number(map.get("score")),
                text(map, "snippet")
        );
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getCategory() {
        return category;
    }

    public String getChunkId() {
        return chunkId;
    }

    public String getKnowledgeType() {
        return knowledgeType;
    }

    public String getProductCategory() {
        return productCategory;
    }

    public String getProductModel() {
        return productModel;
    }

    public String getTitle() {
        return title;
    }

    public int getScore() {
        return score;
    }

    public String getSnippet() {
        return snippet;
    }

    public String getSource() {
        return source;
    }

    public String getVersion() {
        return version;
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String text(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
