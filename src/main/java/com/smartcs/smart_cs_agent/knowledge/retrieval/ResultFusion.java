package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeCandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 使用 RRF 合并关键词和向量排名，并按 chunkId 去重。 */
public class ResultFusion {

    private static final double RRF_K = 60.0;

    public List<KnowledgeCandidate> fuse(List<KnowledgeCandidate> keyword,
                                         List<KnowledgeCandidate> vector) {
        Map<String, KnowledgeCandidate> merged = new LinkedHashMap<>();
        addRanking(merged, keyword, true);
        addRanking(merged, vector, false);
        return merged.values().stream()
                .sorted((left, right) -> Double.compare(right.fusionScore(), left.fusionScore()))
                .toList();
    }

    private void addRanking(Map<String, KnowledgeCandidate> merged,
                            List<KnowledgeCandidate> ranking,
                            boolean keywordChannel) {
        for (int i = 0; i < ranking.size(); i++) {
            KnowledgeCandidate incoming = ranking.get(i);
            KnowledgeCandidate target = merged.computeIfAbsent(
                    incoming.chunk().chunkId(),
                    ignored -> incoming.copy()
            );
            if (keywordChannel) {
                target.keywordScore(incoming.keywordScore());
                for (String term : incoming.matchedTerms()) {
                    target.addMatchedTerm(term);
                }
                for (String field : incoming.matchedFields()) {
                    target.addMatchedField(field);
                }
            } else {
                target.vectorScore(incoming.vectorScore());
            }
            target.fusionScore(target.fusionScore() + 1.0 / (RRF_K + i + 1));
        }
    }
}
