package com.smartcs.smart_cs_agent.knowledge.model;

import java.util.List;

/** 向量通道输出；空候选不等于故障，只有 diagnostics 非空才表示降级。 */
public record VectorRetrievalOutcome(
        List<KnowledgeCandidate> candidates,
        List<KnowledgeSearchDiagnostic> diagnostics
) {
    public VectorRetrievalOutcome {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static VectorRetrievalOutcome success(List<KnowledgeCandidate> candidates) {
        return new VectorRetrievalOutcome(candidates, List.of());
    }
}
