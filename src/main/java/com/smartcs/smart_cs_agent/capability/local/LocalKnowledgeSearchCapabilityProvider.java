package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchProfile;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchResponse;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.tools.KnowledgeSearchResult;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 客服知识库检索的独立本地 Capability Provider。
 *
 * <p>KnowledgeTools 仍是当前关键词、Embedding 和 Qdrant 检索引擎；本类是它与 Agent 契约之间的
 * Anti-Corruption Layer。检索器内部的 matchedFields、queryVariants 和 rerankReason 用于调试，
 * 不应成为 Workflow 必须依赖的公开字段，因此这里只返回精简稳定的 KnowledgeSearchHit。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'mcp-remote'")
public class LocalKnowledgeSearchCapabilityProvider implements CapabilityProvider {

    public static final String PROVIDER_ID = "local-knowledge-search";
    private static final int DEFAULT_TOP_K = 3;

    private final KnowledgeTools knowledgeTools;
    private final CapabilityDefinition definition;

    public LocalKnowledgeSearchCapabilityProvider(KnowledgeTools knowledgeTools) {
        this.knowledgeTools = knowledgeTools;
        this.definition = buildDefinition();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Collection<CapabilityDefinition> definitions() {
        return List.of(definition);
    }

    @Override
    public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
        String query = textArgument(call, "query");
        if (query.isBlank()) {
            return CapabilityResult.businessRejected(
                    CapabilityIds.KNOWLEDGE_SEARCH,
                    PROVIDER_ID,
                    "MISSING_ARGUMENT",
                    "query is required",
                    null
            );
        }

        KnowledgeType type = KnowledgeType.parse(
                firstNonBlank(textArgument(call, "knowledge_type"), textArgument(call, "profile")),
                null
        );
        KnowledgeSearchProfile profile = new KnowledgeSearchProfile(
                type,
                textArgument(call, "product_category"),
                textArgument(call, "product_model"),
                textArgument(call, "version")
        );
        int topK = intArgument(call, "top_k", DEFAULT_TOP_K);
        KnowledgeSearchResponse response = knowledgeTools.searchWithDiagnostics(query, topK, profile);
        List<KnowledgeSearchResult> internalResults = response.results();
        List<KnowledgeSearchHit> hits = internalResults.stream()
                .map(this::toContract)
                .toList();
        List<Map<String, Object>> diagnostics = response.diagnostics().stream()
                .map(diagnostic -> diagnostic.toMap())
                .toList();
        return CapabilityResult.success(
                CapabilityIds.KNOWLEDGE_SEARCH,
                PROVIDER_ID,
                knowledgeTools.formatSearchResult(query, internalResults),
                hits,
                Map.of("diagnostics", diagnostics)
        );
    }

    private KnowledgeSearchHit toContract(KnowledgeSearchResult result) {
        return new KnowledgeSearchHit(
                result.getDocumentId(),
                result.getChunkId(),
                result.getKnowledgeType().name(),
                result.getCategory(),
                result.getProductCategory(),
                result.getProductModel(),
                result.getTitle(),
                result.getSource(),
                result.getVersion(),
                result.getScore(),
                result.getSnippet()
        );
    }

    private String textArgument(CapabilityCall call, String key) {
        Object value = call.getArguments().get(key);
        return value == null ? "" : value.toString().trim();
    }

    private int intArgument(CapabilityCall call, String key, int fallback) {
        Object value = call.getArguments().get(key);
        if (value instanceof Number number) {
            return Math.max(1, Math.min(10, number.intValue()));
        }
        return fallback;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private CapabilityDefinition buildDefinition() {
        Map<String, Object> hitSchema = Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("document_id", Map.of("type", "string")),
                        Map.entry("chunk_id", Map.of("type", "string")),
                        Map.entry("knowledge_type", Map.of("type", "string")),
                        Map.entry("category", Map.of("type", "string")),
                        Map.entry("product_category", Map.of("type", "string")),
                        Map.entry("product_model", Map.of("type", "string")),
                        Map.entry("title", Map.of("type", "string")),
                        Map.entry("source", Map.of("type", "string")),
                        Map.entry("version", Map.of("type", "string")),
                        Map.entry("score", Map.of("type", "integer")),
                        Map.entry("snippet", Map.of("type", "string"))
                )
        );
        return new CapabilityDefinition(
                CapabilityIds.KNOWLEDGE_SEARCH,
                "2.0.0",
                "检索政策或商品故障知识；实时订单、库存和售后进度不进入本能力",
                Map.of(
                        "type", "object",
                        "properties", Map.ofEntries(
                                Map.entry("query", Map.of("type", "string", "description", "用户问题")),
                                Map.entry("knowledge_type", Map.of(
                                        "type", "string",
                                        "enum", List.of("POLICY", "TROUBLESHOOTING")
                                )),
                                Map.entry("profile", Map.of(
                                        "type", "string",
                                        "description", "knowledge_type 的兼容别名"
                                )),
                                Map.entry("product_category", Map.of("type", "string")),
                                Map.entry("product_model", Map.of("type", "string")),
                                Map.entry("version", Map.of("type", "string")),
                                Map.entry("top_k", Map.of("type", "integer", "minimum", 1, "maximum", 10))
                        ),
                        "required", List.of("query")
                ),
                Map.of("type", "array", "items", hitSchema),
                false,
                false,
                false,
                false,
                10_000,
                CapabilityRiskLevel.READ_ONLY
        );
    }
}
