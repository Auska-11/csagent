package com.smartcs.smart_cs_agent.knowledge.retrieval;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeCandidate;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeQueryPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 标题、关键词、正文加权的精确检索。
 *
 * <p>实现没有倒排索引、词频/逆文档频率和 BM25 公式，因此明确命名为 KeywordRetriever。</p>
 */
public class KeywordRetriever {

    private final List<KnowledgeChunk> chunks;
    private final KnowledgeMetadataFilter metadataFilter;

    public KeywordRetriever(List<KnowledgeChunk> chunks, KnowledgeMetadataFilter metadataFilter) {
        this.chunks = List.copyOf(chunks);
        this.metadataFilter = metadataFilter;
    }

    public List<KnowledgeCandidate> retrieve(KnowledgeQueryPlan plan, int limit) {
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            if (!metadataFilter.matches(chunk, plan)) {
                continue;
            }
            KnowledgeCandidate candidate = score(chunk, plan);
            if (candidate.keywordScore() > 0) {
                candidates.add(candidate);
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble((KnowledgeCandidate candidate) -> candidate.keywordScore())
                        .reversed()
                        .thenComparing(candidate -> candidate.chunk().chunkId()))
                .limit(Math.max(1, limit))
                .toList();
    }

    private KnowledgeCandidate score(KnowledgeChunk chunk, KnowledgeQueryPlan plan) {
        String title = normalize(chunk.title());
        String keywords = normalize(String.join(" ", chunk.keywords()));
        String content = normalize(chunk.content());
        KnowledgeCandidate candidate = new KnowledgeCandidate(chunk);
        double score = 0;

        for (String rawTerm : plan.terms()) {
            String term = normalize(rawTerm);
            if (term.isBlank()) {
                continue;
            }
            if (title.contains(term)) {
                score += 16;
                candidate.addMatchedTerm(term);
                candidate.addMatchedField("title");
            }
            if (keywords.contains(term)) {
                score += 11;
                candidate.addMatchedTerm(term);
                candidate.addMatchedField("keywords");
            }
            if (content.contains(term)) {
                score += 5;
                candidate.addMatchedTerm(term);
                candidate.addMatchedField("content");
            }
        }

        String query = normalize(plan.originalQuery());
        if (query.length() >= 3 && (title.contains(query) || content.contains(query))) {
            score += 12;
            candidate.addMatchedField("exact_phrase");
        }
        for (String variant : plan.queryVariants()) {
            if (!variant.matches(".*\\s+.*")) {
                String intentPhrase = normalize(variant);
                if (intentPhrase.length() >= 3 && title.contains(intentPhrase)) {
                    score += 30;
                    candidate.addMatchedField("rewritten_intent");
                }
            }
        }
        if (score > 0
                && !plan.productModel().isBlank()
                && plan.productModel().equalsIgnoreCase(chunk.productModel())) {
            score += 18;
            candidate.addMatchedField("product_model");
        }
        if (score > 0
                && !plan.productCategory().isBlank()
                && plan.productCategory().equalsIgnoreCase(chunk.productCategory())) {
            score += 4;
            candidate.addMatchedField("product_category");
        }

        score += candidate.matchedFields().size() * 2.0;
        candidate.keywordScore(score);
        return candidate;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replace("７", "7")
                .replace("七日", "七天")
                .replace("7天", "七天")
                .trim();
    }
}
