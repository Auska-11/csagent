package com.smartcs.smart_cs_agent.controller;

import java.util.List;

/**
 * 前端提交的单条 Eval case 合并项。
 *
 * 它比正式评测 case 多一个 sourceCandidateId，
 * 用来证明这条 case 确实来自已经 APPROVED 的反馈候选样本。
 */
public class EvalCaseMergeItem {

    private String sourceCandidateId;
    private String id;
    private String candidateReply;
    private List<String> requiredKeywords;
    private List<String> forbiddenKeywords;
    private boolean expectedPass;

    public EvalCaseMergeItem() {
    }

    public EvalCaseMergeItem(String sourceCandidateId,
                             String id,
                             String candidateReply,
                             List<String> requiredKeywords,
                             List<String> forbiddenKeywords,
                             boolean expectedPass) {
        this.sourceCandidateId = sourceCandidateId;
        this.id = id;
        this.candidateReply = candidateReply;
        this.requiredKeywords = requiredKeywords;
        this.forbiddenKeywords = forbiddenKeywords;
        this.expectedPass = expectedPass;
    }

    public String getSourceCandidateId() {
        return sourceCandidateId;
    }

    public void setSourceCandidateId(String sourceCandidateId) {
        this.sourceCandidateId = sourceCandidateId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCandidateReply() {
        return candidateReply;
    }

    public void setCandidateReply(String candidateReply) {
        this.candidateReply = candidateReply;
    }

    public List<String> getRequiredKeywords() {
        return requiredKeywords == null ? List.of() : requiredKeywords;
    }

    public void setRequiredKeywords(List<String> requiredKeywords) {
        this.requiredKeywords = requiredKeywords == null ? List.of() : List.copyOf(requiredKeywords);
    }

    public List<String> getForbiddenKeywords() {
        return forbiddenKeywords == null ? List.of() : forbiddenKeywords;
    }

    public void setForbiddenKeywords(List<String> forbiddenKeywords) {
        this.forbiddenKeywords = forbiddenKeywords == null ? List.of() : List.copyOf(forbiddenKeywords);
    }

    public boolean isExpectedPass() {
        return expectedPass;
    }

    public void setExpectedPass(boolean expectedPass) {
        this.expectedPass = expectedPass;
    }
}
