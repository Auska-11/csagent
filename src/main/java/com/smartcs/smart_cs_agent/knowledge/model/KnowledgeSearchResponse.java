package com.smartcs.smart_cs_agent.knowledge.model;

import com.smartcs.smart_cs_agent.tools.KnowledgeSearchResult;

import java.util.List;

/** KnowledgeSearchService 的完整响应；旧调用方仍可只读取 results。 */
public record KnowledgeSearchResponse(
        List<KnowledgeSearchResult> results,
        List<KnowledgeSearchDiagnostic> diagnostics
) {
    public KnowledgeSearchResponse {
        results = results == null ? List.of() : List.copyOf(results);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static KnowledgeSearchResponse empty() {
        return new KnowledgeSearchResponse(List.of(), List.of());
    }
}
