package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
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

        List<KnowledgeSearchResult> internalResults = knowledgeTools.search(query, DEFAULT_TOP_K);
        List<KnowledgeSearchHit> hits = internalResults.stream()
                .map(this::toContract)
                .toList();
        return CapabilityResult.success(
                CapabilityIds.KNOWLEDGE_SEARCH,
                PROVIDER_ID,
                knowledgeTools.formatSearchResult(query, internalResults),
                hits
        );
    }

    private KnowledgeSearchHit toContract(KnowledgeSearchResult result) {
        return new KnowledgeSearchHit(
                result.getDocumentId(),
                result.getCategory(),
                result.getTitle(),
                result.getScore(),
                result.getSnippet()
        );
    }

    private String textArgument(CapabilityCall call, String key) {
        Object value = call.getArguments().get(key);
        return value == null ? "" : value.toString().trim();
    }

    private CapabilityDefinition buildDefinition() {
        Map<String, Object> hitSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "document_id", Map.of("type", "string"),
                        "category", Map.of("type", "string"),
                        "title", Map.of("type", "string"),
                        "score", Map.of("type", "integer"),
                        "snippet", Map.of("type", "string")
                )
        );
        return new CapabilityDefinition(
                CapabilityIds.KNOWLEDGE_SEARCH,
                "1.0.0",
                "检索客服知识库，用于回答政策、保修、发票、配送等规则问题",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "用户提出的客服政策问题")
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
