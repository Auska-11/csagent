package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeCandidate;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;

import java.util.List;

/** 可解释的规则重排：字段加权分、向量相似度、RRF、双路一致性和型号精确命中。 */
public class KnowledgeReranker {

    private static final double RRF_K = 60.0;
    private static final double RRF_MAX_CHANNELS = 2.0;
    private static final double RRF_MAX_CONTRIBUTION = 8.0;

    public List<KnowledgeCandidate> rerank(KnowledgeQueryPlan plan,
                                            List<KnowledgeCandidate> candidates) {
        for (KnowledgeCandidate candidate : candidates) {
            double keywordPart = Math.min(85, Math.log1p(candidate.keywordScore()) * 16);
            double vectorPart = candidate.vectorScore() * 45;
            // RRF 原值约为 1/(60+rank)，双路第一名总和约 0.0328。归一化后最多贡献 8 分，
            // 足以影响接近候选的顺序，但不会压过关键词（最高 85）、向量（最高 45）或型号过滤。
            double normalizedRrf = Math.min(
                    1.0,
                    candidate.fusionScore() / (RRF_MAX_CHANNELS / (RRF_K + 1.0))
            );
            double rrfContribution = normalizedRrf * RRF_MAX_CONTRIBUTION;
            double dualChannelBonus = candidate.channels().size() > 1 ? 12 : 0;
            double modelBonus = !plan.productModel().isBlank()
                    && plan.productModel().equalsIgnoreCase(candidate.chunk().productModel()) ? 15 : 0;
            double score = keywordPart + vectorPart + rrfContribution + dualChannelBonus + modelBonus;
            candidate.rerankScore(Math.min(100, score));
            candidate.rerankReason(
                    ("ruleReranker(keyword=%.1f, vector=%.4f, rrf=%.6f, "
                            + "rrfContribution=%.2f, dualChannelBonus=%.1f, modelBonus=%.1f)")
                            .formatted(
                                    keywordPart,
                                    candidate.vectorScore(),
                                    candidate.fusionScore(),
                                    rrfContribution,
                                    dualChannelBonus,
                                    modelBonus
                            )
            );
        }
        return candidates.stream()
                .sorted((left, right) -> {
                    int score = Double.compare(right.rerankScore(), left.rerankScore());
                    return score != 0 ? score : left.chunk().chunkId().compareTo(right.chunk().chunkId());
                })
                .toList();
    }
}
