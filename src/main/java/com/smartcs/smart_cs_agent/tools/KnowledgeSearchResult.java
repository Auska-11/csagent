package com.smartcs.smart_cs_agent.tools;

import java.util.List;

/**
 * 知识库检索结果。
 *
 * 【和 KnowledgeDocument 的区别】
 * KnowledgeDocument 表示完整文档；
 * KnowledgeSearchResult 表示“某次查询命中的结果”，所以会多出：
 * - score：本次查询的相关性分数
 * - snippet：命中的片段，方便交给 LLM 或 Trace 展示
 * - matchedTerms / matchedFields：方便解释“为什么命中这篇文档”
 * - vectorScore：本地轻量向量相似度分数，用于解释向量召回信号
 */
public class KnowledgeSearchResult {

    /** 命中的文档 ID */
    private final String documentId;

    /** 命中文档分类 */
    private final String category;

    /** 命中文档标题 */
    private final String title;

    /** 相关性分数，分数越高表示越相关 */
    private final int score;

    /** 命中的正文片段 */
    private final String snippet;

    /** 本次检索命中的关键词 */
    private final List<String> matchedTerms;

    /** 命中的字段，例如 title、keywords、content */
    private final List<String> matchedFields;

    /** 查询改写后的候选表达，便于 Trace 或调试时理解召回过程 */
    private final List<String> queryVariants;

    /** 简短说明 rerank 为什么把它排到当前位置 */
    private final String rerankReason;

    /** 本地向量相似度分数，范围大致是 0 到 100 */
    private final double vectorScore;

    public KnowledgeSearchResult(String documentId, String category, String title, int score, String snippet) {
        this(documentId, category, title, score, snippet, List.of(), List.of(), List.of(), "", 0);
    }

    public KnowledgeSearchResult(String documentId,
                                 String category,
                                 String title,
                                 int score,
                                 String snippet,
                                 List<String> matchedTerms,
                                 List<String> matchedFields,
                                 List<String> queryVariants,
                                 String rerankReason) {
        this(documentId, category, title, score, snippet, matchedTerms, matchedFields, queryVariants, rerankReason, 0);
    }

    public KnowledgeSearchResult(String documentId,
                                 String category,
                                 String title,
                                 int score,
                                 String snippet,
                                 List<String> matchedTerms,
                                 List<String> matchedFields,
                                 List<String> queryVariants,
                                 String rerankReason,
                                 double vectorScore) {
        this.documentId = documentId;
        this.category = category;
        this.title = title;
        this.score = score;
        this.snippet = snippet;
        this.matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        this.matchedFields = matchedFields == null ? List.of() : List.copyOf(matchedFields);
        this.queryVariants = queryVariants == null ? List.of() : List.copyOf(queryVariants);
        this.rerankReason = rerankReason == null ? "" : rerankReason;
        this.vectorScore = vectorScore;
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

    public List<String> getMatchedTerms() {
        return matchedTerms;
    }

    public List<String> getMatchedFields() {
        return matchedFields;
    }

    public List<String> getQueryVariants() {
        return queryVariants;
    }

    public String getRerankReason() {
        return rerankReason;
    }

    public double getVectorScore() {
        return vectorScore;
    }

    /**
     * 转成适合放进 Prompt 的文本。
     */
    public String toPromptText() {
        return "标题：" + title + "\n" +
                "分类：" + category + "\n" +
                "相关度：" + score + "\n" +
                "命中词：" + String.join(", ", matchedTerms) + "\n" +
                "命中字段：" + String.join(", ", matchedFields) + "\n" +
                "向量分：" + String.format("%.2f", vectorScore) + "\n" +
                "排序原因：" + rerankReason + "\n" +
                "片段：" + snippet;
    }
}
