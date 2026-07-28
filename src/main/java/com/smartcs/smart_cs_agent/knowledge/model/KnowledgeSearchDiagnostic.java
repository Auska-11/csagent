package com.smartcs.smart_cs_agent.knowledge.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** 一次检索的安全结构化诊断；用于向 Capability/Trace 边界显式传递通道降级。 */
public record KnowledgeSearchDiagnostic(
        String eventName,
        String stage,
        String errorType,
        String errorSummary,
        String profile,
        KnowledgeType knowledgeType
) {

    public static final String VECTOR_RETRIEVAL_DEGRADED = "vector_retrieval_degraded";

    public KnowledgeSearchDiagnostic {
        eventName = safe(eventName);
        stage = safe(stage);
        errorType = safe(errorType);
        errorSummary = safe(errorSummary);
        profile = safe(profile);
        knowledgeType = knowledgeType == null ? KnowledgeType.TROUBLESHOOTING : knowledgeType;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventName", eventName);
        data.put("stage", stage);
        data.put("errorType", errorType);
        data.put("errorSummary", errorSummary);
        data.put("profile", profile);
        data.put("knowledgeType", knowledgeType.name());
        return Map.copyOf(data);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
