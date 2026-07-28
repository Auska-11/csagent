package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeCandidate;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import com.smartcs.smart_cs_agent.knowledge.retrieval.KnowledgeReranker;
import com.smartcs.smart_cs_agent.knowledge.retrieval.ResultFusion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRerankerTest {

    @Test
    void shouldUseRrfToLiftDualChannelCandidateWhenRankingsConflict() {
        KnowledgeCandidate keywordFirst = keyword("keyword-first", 10);
        KnowledgeCandidate sharedFromKeyword = keyword("shared", 9);
        KnowledgeCandidate vectorFirst = vector("vector-first", 0.78);
        KnowledgeCandidate sharedFromVector = vector("shared", 0.77);

        List<KnowledgeCandidate> fused = new ResultFusion().fuse(
                List.of(keywordFirst, sharedFromKeyword),
                List.of(vectorFirst, sharedFromVector)
        );

        assertEquals(3, fused.size());
        KnowledgeCandidate shared = find(fused, "shared");
        assertEquals(List.of("keyword", "vector"), shared.channels());
        assertTrue(shared.fusionScore() > keywordFirst.fusionScore());

        KnowledgeCandidate withoutRrf = shared.copy();
        withoutRrf.fusionScore(0);
        KnowledgeReranker reranker = new KnowledgeReranker();
        double scoreWithoutRrf = reranker.rerank(plan(), List.of(withoutRrf)).get(0).rerankScore();
        List<KnowledgeCandidate> ranked = reranker.rerank(plan(), fused);

        assertEquals("shared", ranked.get(0).chunk().chunkId());
        assertTrue(shared.rerankScore() > scoreWithoutRrf);
        assertTrue(shared.rerankReason().contains("rrfContribution="));
        assertTrue(shared.rerankReason().contains("dualChannelBonus=12.0"));
    }

    private KnowledgeCandidate keyword(String id, double score) {
        KnowledgeCandidate candidate = new KnowledgeCandidate(chunk(id));
        candidate.keywordScore(score);
        return candidate;
    }

    private KnowledgeCandidate vector(String id, double score) {
        KnowledgeCandidate candidate = new KnowledgeCandidate(chunk(id));
        candidate.vectorScore(score);
        return candidate;
    }

    private KnowledgeCandidate find(List<KnowledgeCandidate> candidates, String chunkId) {
        return candidates.stream()
                .filter(candidate -> chunkId.equals(candidate.chunk().chunkId()))
                .findFirst()
                .orElseThrow();
    }

    private KnowledgeQueryPlan plan() {
        return new KnowledgeQueryPlan(
                "耳机断连",
                KnowledgeType.TROUBLESHOOTING,
                "headphones",
                "",
                "",
                List.of("耳机断连"),
                List.of("耳机", "断连")
        );
    }

    private KnowledgeChunk chunk(String id) {
        return new KnowledgeChunk(
                "troubleshooting/" + id + ".md",
                id,
                KnowledgeType.TROUBLESHOOTING,
                "troubleshooting",
                "headphones",
                "",
                id,
                "test-manual",
                "1.0",
                null,
                "测试内容",
                List.of("断连")
        );
    }
}
