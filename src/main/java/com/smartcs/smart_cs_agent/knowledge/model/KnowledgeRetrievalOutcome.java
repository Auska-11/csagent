package com.smartcs.smart_cs_agent.knowledge.model;

import java.util.List;

/** 检索策略的显式输出，同时携带候选和非致命降级诊断。 */
public record KnowledgeRetrievalOutcome(
        List<KnowledgeCandidate> candidates,
        List<KnowledgeSearchDiagnostic> diagnostics
) {
    public KnowledgeRetrievalOutcome {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static KnowledgeRetrievalOutcome success(List<KnowledgeCandidate> candidates) {
        return new KnowledgeRetrievalOutcome(candidates, List.of());
    }
}
