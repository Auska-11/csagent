package com.smartcs.smart_cs_agent.knowledge.model;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 两路召回、融合和重排之间传递的内部候选。 */
public final class KnowledgeCandidate {

    private final KnowledgeChunk chunk;
    private double keywordScore;
    private double vectorScore;
    private double fusionScore;
    private double rerankScore;
    private final Set<String> matchedTerms = new LinkedHashSet<>();
    private final Set<String> matchedFields = new LinkedHashSet<>();
    private final Set<String> channels = new LinkedHashSet<>();
    private String rerankReason = "";

    public KnowledgeCandidate(KnowledgeChunk chunk) {
        this.chunk = chunk;
    }

    public KnowledgeCandidate copy() {
        KnowledgeCandidate copy = new KnowledgeCandidate(chunk);
        copy.keywordScore = keywordScore;
        copy.vectorScore = vectorScore;
        copy.fusionScore = fusionScore;
        copy.rerankScore = rerankScore;
        copy.matchedTerms.addAll(matchedTerms);
        copy.matchedFields.addAll(matchedFields);
        copy.channels.addAll(channels);
        copy.rerankReason = rerankReason;
        return copy;
    }

    public KnowledgeChunk chunk() {
        return chunk;
    }

    public double keywordScore() {
        return keywordScore;
    }

    public void keywordScore(double keywordScore) {
        this.keywordScore = Math.max(this.keywordScore, keywordScore);
        if (keywordScore > 0) {
            channels.add("keyword");
        }
    }

    public double vectorScore() {
        return vectorScore;
    }

    public void vectorScore(double vectorScore) {
        this.vectorScore = Math.max(this.vectorScore, vectorScore);
        if (vectorScore > 0) {
            channels.add("vector");
            matchedFields.add("embedding_vector");
        }
    }

    public double fusionScore() {
        return fusionScore;
    }

    public void fusionScore(double fusionScore) {
        this.fusionScore = fusionScore;
    }

    public double rerankScore() {
        return rerankScore;
    }

    public void rerankScore(double rerankScore) {
        this.rerankScore = rerankScore;
    }

    public void addMatchedTerm(String term) {
        matchedTerms.add(term);
    }

    public void addMatchedField(String field) {
        matchedFields.add(field);
    }

    public List<String> matchedTerms() {
        return List.copyOf(matchedTerms);
    }

    public List<String> matchedFields() {
        return List.copyOf(matchedFields);
    }

    public List<String> channels() {
        return List.copyOf(channels);
    }

    public String rerankReason() {
        return rerankReason;
    }

    public void rerankReason(String rerankReason) {
        this.rerankReason = rerankReason == null ? "" : rerankReason;
    }
}
