package com.smartcs.smart_cs_agent.tools;

import java.util.List;

/**
 * 知识库文档模型。
 *
 * 【为什么单独建这个类？】
 * Markdown 文件是给人看的，Agent 检索时需要的是结构化信息：
 * - id：文档唯一标识，方便 Trace 和排查
 * - category：分类，例如 policy、logistics
 * - title：标题，通常比正文更适合做高权重匹配
 * - content：完整正文
 * - keywords：人工维护的关键词，提升第一版检索稳定性
 */
public class KnowledgeDocument {

    /** 文档唯一标识，例如 policy/return_policy.md */
    private final String id;

    /** 文档分类，例如 policy、logistics、service */
    private final String category;

    /** 文档标题，例如 退换货政策 */
    private final String title;

    /** Markdown 原文内容 */
    private final String content;

    /** 人工维护的关键词，用于提升轻量检索命中率 */
    private final List<String> keywords;

    public KnowledgeDocument(String id, String category, String title, String content, List<String> keywords) {
        this.id = id;
        this.category = category;
        this.title = title;
        this.content = content;
        this.keywords = List.copyOf(keywords);
    }

    public String getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public List<String> getKeywords() {
        return keywords;
    }
}
