package com.smartcs.smart_cs_agent.knowledge.model;

/** 一次知识检索请求。 */
public record KnowledgeSearchRequest(
        String query,
        int topK,
        KnowledgeSearchProfile profile
) {
    public KnowledgeSearchRequest {
        query = query == null ? "" : query.trim();
        topK = topK <= 0 ? 3 : topK;
        profile = profile == null ? KnowledgeSearchProfile.auto() : profile;
    }
}
