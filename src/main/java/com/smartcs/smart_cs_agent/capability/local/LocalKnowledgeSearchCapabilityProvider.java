package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSidecarProperties;
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
 *
 * <p>租户隔离与 X3 的商品目录同源：演示知识库（演示商品的故障排查与政策文档）不能出现在
 * 闲鱼真实买家的检索结果里——压测 #3 实测，xianyu 租户问“连不上蓝牙”召回了演示商品
 * “蓝牙耳机 Pro Max 用户手册”的排查步骤。闲鱼租户的商品快照文档带 productcategory=xianyu
 * 元数据（X2 落盘约束），据此把闲鱼租户的检索 Profile 强制限定到该品类。</p>
 */
@Component
@ConditionalOnExpression("'${smartcs.capability.provider-mode:local}' != 'mcp-remote'")
public class LocalKnowledgeSearchCapabilityProvider implements CapabilityProvider {

    public static final String PROVIDER_ID = "local-knowledge-search";
    private static final int DEFAULT_TOP_K = 3;

    private final KnowledgeTools knowledgeTools;
    private final XianyuSidecarProperties xianyuProperties;
    private final CapabilityDefinition definition;

    public LocalKnowledgeSearchCapabilityProvider(KnowledgeTools knowledgeTools,
                                                  XianyuSidecarProperties xianyuProperties) {
        this.knowledgeTools = knowledgeTools;
        this.xianyuProperties = xianyuProperties;
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
                tenantScopedCategory(textArgument(call, "product_category"), context),
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

    /**
     * 闲鱼租户强制限定商品品类为 xianyu：该品类下只有闲鱼商品快照文档，演示租户的排查与政策
     * 文档（headphones/watch/charger/cable 及 policy/service）因品类元数据不匹配被过滤，
     * 演示知识永不进入真实买家渠道。
     */
    private String tenantScopedCategory(String requestedCategory, ExecutionContext context) {
        if (isXianyuTenant(context)) {
            return XianyuKnowledgeProductCatalog.PRODUCT_CATEGORY;
        }
        return requestedCategory;
    }

    /** 闲鱼租户名来自 smartcs.xianyu.tenant-id，与买家 JWT 的 tenant_id 同源。 */
    private boolean isXianyuTenant(ExecutionContext context) {
        String tenantId = context.getTenantId();
        return tenantId != null
                && !tenantId.isBlank()
                && xianyuProperties.getTenantId().equalsIgnoreCase(tenantId.trim());
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
