package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeCandidate;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.tools.RagProperties;

import java.util.List;

/** 统一可靠性闸门；空结果表示真正的 knowledge miss。 */
public class RelevanceGate {

    private final RagProperties properties;

    public RelevanceGate(RagProperties properties) {
        this.properties = properties;
    }

    public List<KnowledgeCandidate> reliable(KnowledgeType type,
                                             List<KnowledgeCandidate> candidates,
                                             int topK) {
        double threshold = type == KnowledgeType.POLICY
                ? properties.getPolicyMinScore()
                : properties.getTroubleshootingMinScore();
        return candidates.stream()
                .filter(candidate -> candidate.rerankScore() >= threshold)
                .limit(Math.max(1, topK))
                .toList();
    }
}
