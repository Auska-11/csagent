package com.smartcs.smart_cs_agent.tools;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识库工具。
 *
 * 【当前第一版怎么做？】
 * 先使用 Markdown 文件 + 关键词打分 + 本地轻量向量召回。
 *
 * 这样做的好处：
 * - 新手更容易看懂
 * - 检索结果可解释，Trace 里能看到命中了哪篇文档
 * - 后续可以平滑升级为真实 embedding 模型和向量数据库
 *
 * 【它解决什么问题？】
 * 政策类问题不能让 LLM 自己猜，例如“超过七天还能退吗？”。
 * Agent 应该先检索知识库，再基于知识库结果回答，降低幻觉风险。
 */
@Component
public class KnowledgeTools {

    /** 默认最多返回 3 条结果，避免 Prompt 里塞太多无关内容 */
    private static final int DEFAULT_TOP_K = 3;

    /** 相关度低于这个阈值时，认为没有可靠命中 */
    private static final int MIN_SCORE = 6;

    /** 当前加载到内存里的知识库文档 */
    private final List<KnowledgeDocument> documents;

    private final RagProperties ragProperties;
    private final TextEmbeddingClient embeddingClient;
    private final KnowledgeVectorStore vectorStore;
    private volatile boolean vectorIndexReady = false;

    /**
     * Spring 创建 Bean 时会走这个构造函数，从 classpath 加载 Markdown 文档。
     */
    public KnowledgeTools() {
        this(loadDocumentsFromClasspath(), new RagProperties(), null, null);
    }

    /**
     * Spring 运行时使用的构造函数。
     */
    @Autowired
    public KnowledgeTools(RagProperties ragProperties,
                          TextEmbeddingClient embeddingClient,
                          KnowledgeVectorStore vectorStore) {
        this(loadDocumentsFromClasspath(), ragProperties, embeddingClient, vectorStore);
    }

    /**
     * 测试专用构造函数。
     *
     * 单元测试里直接传入几篇文档，不依赖 Spring 和 classpath，
     * 测试会更轻、更稳定。
     */
    public KnowledgeTools(List<KnowledgeDocument> documents) {
        this(documents, new RagProperties(), null, null);
    }

    KnowledgeTools(List<KnowledgeDocument> documents,
                   RagProperties ragProperties,
                   TextEmbeddingClient embeddingClient,
                   KnowledgeVectorStore vectorStore) {
        this.documents = List.copyOf(documents);
        this.ragProperties = ragProperties == null ? new RagProperties() : ragProperties;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    /**
     * 对外暴露的知识库搜索方法。
     *
     * @param query 用户问题
     * @return 适合交给 LLM 总结的检索文本
     */
    public String searchKnowledge(String query) {
        return formatSearchResult(query, search(query, DEFAULT_TOP_K));
    }

    /**
     * 返回结构化检索结果。
     *
     * ToolRegistry 会把这个结果放到 ToolResult.data 里，
     * 后续做评测时可以直接判断命中了哪篇文档。
     */
    public List<KnowledgeSearchResult> search(String query, int topK) {
        if (ragProperties.isEmbeddingQdrantMode() && embeddingClient != null && vectorStore != null) {
            return searchByEmbedding(query, topK);
        }

        return searchLocal(query, topK);
    }

    private List<KnowledgeSearchResult> searchLocal(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        SearchPlan searchPlan = buildSearchPlan(query);
        if (searchPlan.terms().isEmpty()) {
            return List.of();
        }

        return documents.stream()
                .map(document -> toSearchResult(document, searchPlan))
                .filter(result -> result.getScore() >= MIN_SCORE)
                .sorted(rerankComparator())
                .limit(topK)
                .toList();
    }

    private List<KnowledgeSearchResult> searchByEmbedding(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        try {
            ensureVectorIndex();
            SearchPlan searchPlan = buildSearchPlan(query);
            List<Double> queryEmbedding = embeddingClient.embed(query);
            List<KnowledgeSearchResult> vectorResults = vectorStore.search(queryEmbedding, topK)
                    .stream()
                    .map(hit -> toVectorSearchResult(hit, searchPlan))
                    .filter(result -> result.getDocumentId() != null && !result.getDocumentId().isBlank())
                    .limit(topK)
                    .toList();

            // 如果向量库没有返回结果，回退到本地检索，避免“高级模式”反而降低可用性。
            return vectorResults.isEmpty() ? searchLocal(query, topK) : vectorResults;
        } catch (RuntimeException e) {
            // 真实 embedding 或 Qdrant 暂时不可用时，不应该让客服知识问答直接失败。
            // 这里退回本地 RAG，既保留可用性，也方便面试时讲降级策略。
            return searchLocal(query, topK);
        }
    }

    private synchronized void ensureVectorIndex() {
        if (vectorIndexReady) {
            return;
        }

        List<List<Double>> embeddings = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            embeddings.add(embeddingClient.embed(toEmbeddingText(document)));
        }

        int dimensions = embeddings.isEmpty() || embeddings.get(0).isEmpty()
                ? ragProperties.getEmbedding().getDimensions()
                : embeddings.get(0).size();
        vectorStore.ensureCollection(dimensions);
        vectorStore.upsertDocuments(documents, embeddings);
        vectorIndexReady = true;
    }

    private String toEmbeddingText(KnowledgeDocument document) {
        return document.getTitle() + "\n"
                + "分类：" + document.getCategory() + "\n"
                + "关键词：" + String.join("，", document.getKeywords()) + "\n"
                + preview(document.getContent(), 1200);
    }

    private KnowledgeSearchResult toVectorSearchResult(VectorKnowledgeHit hit, SearchPlan searchPlan) {
        int score = (int) Math.round(hit.score() * 100);
        String content = hit.content() == null || hit.content().isBlank()
                ? hit.title()
                : hit.content();
        String snippet = buildSnippet(content, searchPlan.terms());
        return new KnowledgeSearchResult(
                hit.documentId(),
                hit.category(),
                hit.title(),
                score,
                snippet,
                List.of("embedding"),
                List.of("embedding_vector"),
                searchPlan.queryVariants(),
                "embedding=qdrant, cosineScore=%.4f".formatted(hit.score()),
                score
        );
    }

    /**
     * 把结构化检索结果整理成给 LLM 看的文本。
     *
     * 注意这里会明确提醒“不要编造知识库不存在的政策”，
     * 这是 RAG 场景里很重要的一条约束。
     */
    public String formatSearchResult(String query, List<KnowledgeSearchResult> results) {
        if (query == null || query.isBlank()) {
            return "用户没有提供明确问题，请追问用户想咨询哪类客服政策。";
        }

        if (results.isEmpty()) {
            return "知识库没有找到与问题直接相关的政策。\n" +
                    "用户问题：" + query + "\n" +
                    "建议回复：说明当前没有检索到确定政策，请用户补充订单或问题细节；必要时建议转人工客服。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("知识库检索结果：请只基于以下内容回答，不要编造未出现的政策。\n");
        builder.append("用户问题：").append(query).append("\n\n");

        for (int i = 0; i < results.size(); i++) {
            KnowledgeSearchResult result = results.get(i);
            builder.append(i + 1).append(". ").append(result.toPromptText()).append("\n\n");
        }

        return builder.toString().trim();
    }

    private static List<KnowledgeDocument> loadDocumentsFromClasspath() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources("classpath*:/knowledge/**/*.md");
            List<KnowledgeDocument> loadedDocuments = new ArrayList<>();

            for (Resource resource : resources) {
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String id = buildDocumentId(resource);
                String title = extractTitle(content, resource.getFilename());
                String category = extractCategory(id);
                List<String> keywords = extractKeywords(content, title);

                loadedDocuments.add(new KnowledgeDocument(id, category, title, content, keywords));
            }

            return loadedDocuments;
        } catch (IOException e) {
            throw new IllegalStateException("加载知识库 Markdown 文件失败", e);
        }
    }

    private KnowledgeSearchResult toSearchResult(KnowledgeDocument document, SearchPlan searchPlan) {
        ScoreBreakdown score = scoreDocument(document, searchPlan.terms());
        String snippet = buildSnippet(document.getContent(), searchPlan.terms());
        return new KnowledgeSearchResult(
                document.getId(),
                document.getCategory(),
                document.getTitle(),
                score.totalScore(),
                snippet,
                score.matchedTerms(),
                score.matchedFields(),
                searchPlan.queryVariants(),
                score.rerankReason(),
                score.vectorScore()
        );
    }

    /**
     * 简单相关性打分 + 本地向量召回。
     *
     * 第一版故意保持朴素：
     * - 标题命中权重最高
     * - 人工关键词次之
     * - 正文命中也会加分
     * - 字符 n-gram 向量相似度提供“语义相近但关键词不完全一致”的补充信号
     *
     * 这样面试时可以讲清楚：先做可解释的 baseline，
     * 后续再用 embedding/rerank 优化召回质量。
     */
    private ScoreBreakdown scoreDocument(KnowledgeDocument document, List<String> terms) {
        String title = normalize(document.getTitle());
        String content = normalize(document.getContent());
        String keywords = normalize(String.join(" ", document.getKeywords()));

        int titleScore = 0;
        int keywordScore = 0;
        int contentScore = 0;
        Set<String> matchedTerms = new LinkedHashSet<>();
        Set<String> matchedFields = new LinkedHashSet<>();

        for (String term : terms) {
            if (title.contains(term)) {
                titleScore += 12;
                matchedTerms.add(term);
                matchedFields.add("title");
            }
            if (keywords.contains(term)) {
                keywordScore += 10;
                matchedTerms.add(term);
                matchedFields.add("keywords");
            }
            if (content.contains(term)) {
                contentScore += 4;
                matchedTerms.add(term);
                matchedFields.add("content");
            }
        }

        double vectorScore = calculateVectorScore(document, terms);
        int vectorBoost = vectorScore >= 18 ? (int) Math.round(vectorScore / 3) : 0;
        if (vectorBoost > 0) {
            matchedFields.add("vector");
        }

        // 简单 rerank：如果同一篇文档同时命中标题、关键词和正文，
        // 说明它不是“碰巧正文出现某个词”，而是整体更相关，给一点字段覆盖加分。
        int fieldCoverageBonus = matchedFields.size() * 2;
        int totalScore = titleScore + keywordScore + contentScore + vectorBoost + fieldCoverageBonus;
        String rerankReason = "title=%d, keywords=%d, content=%d, vectorBoost=%d, fieldCoverageBonus=%d"
                .formatted(titleScore, keywordScore, contentScore, vectorBoost, fieldCoverageBonus);

        return new ScoreBreakdown(
                totalScore,
                List.copyOf(matchedTerms),
                List.copyOf(matchedFields),
                rerankReason,
                vectorScore
        );
    }

    /**
     * 本地轻量向量相似度。
     *
     * 真实生产里通常会用 embedding 模型把文本转成向量，再放入向量数据库。
     * 这里为了保持项目可本地运行、CI 不依赖外部模型，先用字符 n-gram 构建“伪向量”：
     * - 适合中文短文本；
     * - 不需要额外依赖；
     * - 能把“配送/送到/物流”这类相近表达纳入排序信号。
     */
    private double calculateVectorScore(KnowledgeDocument document, List<String> terms) {
        if (terms.isEmpty()) {
            return 0;
        }

        String queryText = String.join(" ", terms);
        String documentText = document.getTitle() + " " +
                String.join(" ", document.getKeywords()) + " " +
                preview(document.getContent(), 800);

        Map<String, Double> queryVector = toNgramVector(normalize(queryText));
        Map<String, Double> documentVector = toNgramVector(normalize(documentText));
        return cosineSimilarity(queryVector, documentVector) * 100;
    }

    private Map<String, Double> toNgramVector(String text) {
        Map<String, Double> vector = new HashMap<>();
        String compact = text.replaceAll("\\s+", "");
        if (compact.isBlank()) {
            return vector;
        }

        // 1 字符特征保证短词可召回，2 字符特征让中文短语更有区分度。
        addNgrams(vector, compact, 1, 0.4);
        addNgrams(vector, compact, 2, 1.0);
        return vector;
    }

    private void addNgrams(Map<String, Double> vector, String text, int n, double weight) {
        if (text.length() < n) {
            return;
        }
        for (int i = 0; i <= text.length() - n; i++) {
            String token = text.substring(i, i + n);
            vector.merge(token, weight, Double::sum);
        }
    }

    private double cosineSimilarity(Map<String, Double> left, Map<String, Double> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }

        double dot = 0;
        for (Map.Entry<String, Double> entry : left.entrySet()) {
            dot += entry.getValue() * right.getOrDefault(entry.getKey(), 0.0);
        }

        double leftNorm = norm(left);
        double rightNorm = norm(right);
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (leftNorm * rightNorm);
    }

    private double norm(Map<String, Double> vector) {
        double sum = 0;
        for (double value : vector.values()) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    /**
     * 从用户问题里抽取检索词。
     *
     * 中文没有像英文那样天然用空格分词，所以这里会额外维护一组客服领域关键词。
     * 这不是最终形态，但足够支撑第一版知识库检索。
     */
    private SearchPlan buildSearchPlan(String query) {
        String normalizedQuery = normalize(query);
        List<String> queryVariants = buildQueryVariants(normalizedQuery);
        Set<String> terms = new LinkedHashSet<>();

        for (String variant : queryVariants) {
            for (String part : variant.split("[\\s,，。？?！!、；;：:]+")) {
                if (part.length() >= 2) {
                    terms.add(part);
                }
            }
        }

        String[] domainTerms = {
                "退货", "退款", "换货", "七天", "七天无理由", "超过七天", "质量问题", "运费", "售后",
                "保修", "质保", "维修", "售后检测", "故障",
                "发票", "电子发票", "普通发票", "专用发票", "抬头", "税号",
                "配送", "物流", "发货", "偏远", "偏远地区", "签收", "时效",
                "人工", "人工客服", "转人工", "投诉", "赔偿", "维权"
        };

        for (String term : domainTerms) {
            String normalizedTerm = normalize(term);
            for (String variant : queryVariants) {
                if (variant.contains(normalizedTerm)) {
                    terms.add(normalizedTerm);
                    break;
                }
            }
        }

        return new SearchPlan(new ArrayList<>(terms), queryVariants);
    }

    /**
     * 查询改写。
     *
     * 用户不会总是直接说“保修政策”“配送政策”。
     * 例如“耳机坏了能修吗”其实是在问保修/维修；
     * “什么时候送到”其实是在问配送/物流时效。
     *
     * 第一版先用规则改写，把口语表达扩展成更适合检索的关键词。
     */
    private List<String> buildQueryVariants(String normalizedQuery) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(normalizedQuery);

        if (containsAny(normalizedQuery, "坏", "坏了", "故障", "不能用", "没声音", "失灵", "修吗", "维修")) {
            variants.add("保修 质保 维修 售后检测 质量问题 故障");
        }

        if (containsAny(normalizedQuery, "票", "开票", "发票", "抬头", "税号", "专票", "专用票")) {
            variants.add("发票 电子发票 普通发票 专用发票 增值税专用发票 抬头 税号");
        }

        if (containsAny(normalizedQuery, "送到", "多久到", "几天到", "配送", "快递", "物流", "发货", "签收")) {
            variants.add("配送 物流 发货 时效 签收 偏远地区 配送限制");
        }

        if (containsAny(normalizedQuery, "退", "退款", "换", "七天", "质量问题", "运费")) {
            variants.add("退货 退款 换货 七天无理由 超过七天 质量问题 运费 售后检测");
        }

        if (containsAny(normalizedQuery, "人工", "真人", "投诉", "赔偿", "维权", "差评")) {
            variants.add("人工客服 转人工 投诉 赔偿 维权 人工转接规则");
        }

        return new ArrayList<>(variants);
    }

    private Comparator<KnowledgeSearchResult> rerankComparator() {
        return (left, right) -> {
            int scoreCompare = Integer.compare(right.getScore(), left.getScore());
            if (scoreCompare != 0) {
                return scoreCompare;
            }

            int fieldCoverageCompare = Integer.compare(
                    right.getMatchedFields().size(),
                    left.getMatchedFields().size()
            );
            if (fieldCoverageCompare != 0) {
                return fieldCoverageCompare;
            }

            return left.getDocumentId().compareTo(right.getDocumentId());
        };
    }

    private String buildSnippet(String content, List<String> terms) {
        String compactContent = content
                .replaceAll("(?m)^关键词：.*$", "")
                .replace("#", "")
                .replaceAll("\\s+", " ")
                .trim();

        String normalizedContent = normalize(compactContent);
        int hitIndex = -1;

        for (String term : terms) {
            hitIndex = normalizedContent.indexOf(term);
            if (hitIndex >= 0) {
                break;
            }
        }

        if (hitIndex < 0) {
            return preview(compactContent, 180);
        }

        // 政策文档通常是一小段一小段的，命中点后面的内容往往才包含具体规则。
        // 例如“专用发票”命中后，后面会列出企业名称、税号等资料。
        // 所以这里适当放大片段长度，保证关键政策点能进入 Prompt。
        int start = Math.max(0, hitIndex - 80);
        int end = Math.min(compactContent.length(), hitIndex + 260);
        return preview(compactContent.substring(start, end).trim(), 340);
    }

    private static String buildDocumentId(Resource resource) throws IOException {
        String path = resource.getURL().toString().replace("\\", "/");
        int index = path.indexOf("/knowledge/");
        if (index >= 0) {
            return path.substring(index + "/knowledge/".length());
        }
        return resource.getFilename() == null ? "unknown.md" : resource.getFilename();
    }

    private static String extractCategory(String documentId) {
        int slashIndex = documentId.indexOf("/");
        if (slashIndex > 0) {
            return documentId.substring(0, slashIndex);
        }
        return "general";
    }

    private static String extractTitle(String content, String fallbackName) {
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }

        if (fallbackName == null || fallbackName.isBlank()) {
            return "未命名知识文档";
        }
        return fallbackName.replace(".md", "");
    }

    private static List<String> extractKeywords(String content, String title) {
        List<String> keywords = new ArrayList<>();
        keywords.add(title);

        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("关键词：")) {
                addKeywords(keywords, trimmed.substring("关键词：".length()));
            }
            if (trimmed.toLowerCase(Locale.ROOT).startsWith("keywords:")) {
                addKeywords(keywords, trimmed.substring("keywords:".length()));
            }
        }

        return keywords;
    }

    private static void addKeywords(List<String> keywords, String rawKeywords) {
        for (String keyword : rawKeywords.split("[,，、\\s]+")) {
            if (!keyword.isBlank()) {
                keywords.add(keyword.trim());
            }
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("７", "7")
                .replace("七日", "七天")
                .replace("7 天", "七天")
                .replace("7天", "七天")
                .trim();
    }

    private String preview(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private record SearchPlan(List<String> terms, List<String> queryVariants) {
    }

    private record ScoreBreakdown(int totalScore,
                                  List<String> matchedTerms,
                                  List<String> matchedFields,
                                  String rerankReason,
                                  double vectorScore) {
    }
}
