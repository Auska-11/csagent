package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.knowledge.index.MarkdownKnowledgeDocumentLoader;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchProfile;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchRequest;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchResponse;
import com.smartcs.smart_cs_agent.knowledge.service.KnowledgeSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code knowledge.search} 的兼容门面。
 *
 * <p>检索规划、策略选择、关键词/向量召回、融合、重排和阈值判断均由
 * {@link KnowledgeSearchService} 及其协作者承担，本类只保留旧调用入口与结果格式化。</p>
 */
@Component
public class KnowledgeTools {

    private static final int DEFAULT_TOP_K = 3;

    private final KnowledgeSearchService searchService;

    public KnowledgeTools() {
        this(new KnowledgeSearchService(
                new MarkdownKnowledgeDocumentLoader().load(),
                new RagProperties(),
                null,
                null
        ));
    }

    @Autowired
    public KnowledgeTools(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

    public KnowledgeTools(List<KnowledgeDocument> documents) {
        this(documents, new RagProperties(), null, null);
    }

    KnowledgeTools(List<KnowledgeDocument> documents,
                   RagProperties properties,
                   TextEmbeddingClient embeddingClient,
                   KnowledgeVectorStore vectorStore) {
        this(new KnowledgeSearchService(
                documents,
                properties == null ? new RagProperties() : properties,
                embeddingClient,
                vectorStore
        ));
    }

    public String searchKnowledge(String query) {
        return formatSearchResult(query, search(query, DEFAULT_TOP_K));
    }

    /** 旧入口保留自动类型判断。专业 Workflow 应调用带 Profile 的重载。 */
    public List<KnowledgeSearchResult> search(String query, int topK) {
        return search(query, topK, KnowledgeSearchProfile.auto());
    }

    public List<KnowledgeSearchResult> search(String query,
                                              int topK,
                                              KnowledgeSearchProfile profile) {
        return searchWithDiagnostics(query, topK, profile).results();
    }

    public KnowledgeSearchResponse searchWithDiagnostics(String query,
                                                          int topK,
                                                          KnowledgeSearchProfile profile) {
        return searchService.searchWithDiagnostics(new KnowledgeSearchRequest(query, topK, profile));
    }

    /**
     * 检索结果的 LLM 证据文本：提示词骨架 + {@code toPromptText()}（含相关度/召回通道等调试字段）。
     * 只供 Prompt 与调试消费；买家直接可见的降级话术在
     * {@code LlmActionHandler#knowledgeFallbackAnswer} 用命中标题+片段另行组织
     * （2026-09-05 压测 #3 实测：老降级路径把这段文本原样甩给过真实买家）。
     */
    public String formatSearchResult(String query, List<KnowledgeSearchResult> results) {
        if (query == null || query.isBlank()) {
            return "用户没有提供明确问题，请补充要咨询的政策或商品故障现象。";
        }
        if (results == null || results.isEmpty()) {
            return "知识库没有找到达到可靠性阈值的证据。\n"
                    + "用户问题：" + query + "\n"
                    + "建议：补充商品品类、型号和具体现象；政策问题可联系人工客服确认。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("知识库可靠证据：只能根据以下内容回答，不要编造或补充知识库未出现的事实。\n");
        builder.append("用户问题：").append(query).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            builder.append(i + 1)
                    .append(". ")
                    .append(results.get(i).toPromptText())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }
}
