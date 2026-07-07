package com.smartcs.smart_cs_agent.monitor;

import java.util.List;

/**
 * 由审核通过的反馈候选样本导出的 Eval case 草稿。
 *
 * 【为什么先导出草稿，而不是直接写入正式评测集？】
 * 正式 Eval case 需要非常明确的断言，例如 requiredKeywords 和 forbiddenKeywords。
 * 用户负反馈能说明“这条回复有问题”，但不一定能自动推断出完整断言。
 *
 * 所以第一版先导出草稿，开发者确认后再手动整理进
 * src/test/resources/eval/response_quality_eval_cases.json。
 */
public class FeedbackEvalCaseDraft {

    /** 建议放入评测集的 case id */
    private final String id;

    /** 来源候选样本 ID */
    private final String sourceCandidateId;

    /** 来源反馈 ID */
    private final String sourceFeedbackId;

    /** 来源会话 ID，方便回查上下文 */
    private final String conversationId;

    /** 来源用户 ID */
    private final String userId;

    /** 建议进入哪类评测 */
    private final String suggestedEvalType;

    /** 负反馈问题类型，方便开发者整理草稿时判断应该补哪类断言 */
    private final FeedbackIssueType issueType;

    /** 被用户评价为无用的回复，第一版默认作为负例候选 */
    private final String candidateReply;

    /** 第一版无法自动判断必需关键词，导出后由开发者补齐 */
    private final List<String> requiredKeywords;

    /** 默认保留常见危险承诺和内部工具词，后续可以人工调整 */
    private final List<String> forbiddenKeywords;

    /** 用户负反馈样本默认是负例草稿，所以 expectedPass 默认 false */
    private final boolean expectedPass;

    /** 用户反馈原因 */
    private final String feedbackReason;

    /** 人工审核备注 */
    private final String reviewNote;

    /** 给开发者的人工整理提示 */
    private final String manualTodo;

    /** 草稿生成时间 */
    private final long generatedAt;

    public FeedbackEvalCaseDraft(String id,
                                 String sourceCandidateId,
                                 String sourceFeedbackId,
                                 String conversationId,
                                 String userId,
                                 String suggestedEvalType,
                                 FeedbackIssueType issueType,
                                 String candidateReply,
                                 List<String> requiredKeywords,
                                 List<String> forbiddenKeywords,
                                 boolean expectedPass,
                                 String feedbackReason,
                                 String reviewNote,
                                 String manualTodo,
                                 long generatedAt) {
        this.id = id;
        this.sourceCandidateId = sourceCandidateId;
        this.sourceFeedbackId = sourceFeedbackId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.suggestedEvalType = suggestedEvalType;
        this.issueType = issueType == null ? FeedbackIssueType.OTHER : issueType;
        this.candidateReply = candidateReply;
        this.requiredKeywords = requiredKeywords == null ? List.of() : List.copyOf(requiredKeywords);
        this.forbiddenKeywords = forbiddenKeywords == null ? List.of() : List.copyOf(forbiddenKeywords);
        this.expectedPass = expectedPass;
        this.feedbackReason = feedbackReason;
        this.reviewNote = reviewNote;
        this.manualTodo = manualTodo;
        this.generatedAt = generatedAt;
    }

    public String getId() {
        return id;
    }

    public String getSourceCandidateId() {
        return sourceCandidateId;
    }

    public String getSourceFeedbackId() {
        return sourceFeedbackId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getSuggestedEvalType() {
        return suggestedEvalType;
    }

    public FeedbackIssueType getIssueType() {
        return issueType;
    }

    public String getCandidateReply() {
        return candidateReply;
    }

    public List<String> getRequiredKeywords() {
        return requiredKeywords;
    }

    public List<String> getForbiddenKeywords() {
        return forbiddenKeywords;
    }

    public boolean isExpectedPass() {
        return expectedPass;
    }

    public String getFeedbackReason() {
        return feedbackReason;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public String getManualTodo() {
        return manualTodo;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }
}
