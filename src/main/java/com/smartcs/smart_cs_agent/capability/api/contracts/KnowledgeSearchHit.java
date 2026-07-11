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
    private final String category;
    private final String title;
    private final int score;
    private final String snippet;

    public KnowledgeSearchHit(String documentId,
                              String category,
                              String title,
                              int score,
                              String snippet) {
        this.documentId = safeText(documentId);
        this.category = safeText(category);
        this.title = safeText(title);
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
                text(map.get("documentId")),
                text(map.get("category")),
                text(map.get("title")),
                number(map.get("score")),
                text(map.get("snippet"))
        );
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getCategory() {
        return category;
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

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
