package com.smartcs.smart_cs_agent.monitor;

/**
 * 由用户负反馈沉淀出来的 Eval 候选样本。
 *
 * 【为什么叫 Candidate？】
 * 用户点了“无用”并不代表这条样本一定能直接进入正式评测集：
 * - 可能是用户误点；
 * - 可能缺少完整上下文；
 * - 也可能确实暴露了 Agent 的回复问题。
 *
 * 所以第一版先把它放进“待审核候选池”，后续可以由开发者人工确认后，
 * 再整理成稳定的离线 Eval case。
 */
public class FeedbackEvalCandidate {

    /** 候选样本 ID，方便前端列表和后续导出定位 */
    private final String candidateId;

    /** 这条候选样本来自哪一次用户反馈 */
    private final String feedbackId;

    /** 对应的会话 ID */
    private final String conversationId;

    /** 提交反馈的用户 ID */
    private final String userId;

    /** 当前第一版只会由 UNHELPFUL 反馈生成候选，但保留 rating 方便后续扩展 */
    private final FeedbackRating rating;

    /** 用户反馈原因，例如“没有说明退货时效” */
    private final String reason;

    /** 被用户评价的助手回复内容，前端会在提交反馈时带上 */
    private final String assistantReply;

    /** 建议进入哪类评测，第一版先统一进入回复质量候选 */
    private final String suggestedEvalType;

    /** 负反馈问题类型，帮助 Monitor 统计“用户为什么觉得回答无用” */
    private final FeedbackIssueType issueType;

    /** 候选样本状态，初始为 PENDING_REVIEW，审核后变为 APPROVED 或 REJECTED */
    private final FeedbackEvalCandidateStatus status;

    /** 人工审核备注，例如“信息完整，可补进退货回复质量评测” */
    private final String reviewNote;

    /** 人工审核时间，未审核时为 0 */
    private final long reviewedAt;

    /** 候选样本生成时间，毫秒时间戳 */
    private final long timestamp;

    public FeedbackEvalCandidate(String candidateId,
                                 String feedbackId,
                                 String conversationId,
                                 String userId,
                                 FeedbackRating rating,
                                 String reason,
                                 String assistantReply,
                                 String suggestedEvalType,
                                 long timestamp) {
        this(candidateId,
                feedbackId,
                conversationId,
                userId,
                rating,
                reason,
                assistantReply,
                suggestedEvalType,
                FeedbackIssueType.OTHER,
                FeedbackEvalCandidateStatus.PENDING_REVIEW,
                "",
                0,
                timestamp);
    }

    public FeedbackEvalCandidate(String candidateId,
                                 String feedbackId,
                                 String conversationId,
                                 String userId,
                                 FeedbackRating rating,
                                 String reason,
                                 String assistantReply,
                                 String suggestedEvalType,
                                 FeedbackIssueType issueType,
                                 long timestamp) {
        this(candidateId,
                feedbackId,
                conversationId,
                userId,
                rating,
                reason,
                assistantReply,
                suggestedEvalType,
                issueType,
                FeedbackEvalCandidateStatus.PENDING_REVIEW,
                "",
                0,
                timestamp);
    }

    public FeedbackEvalCandidate(String candidateId,
                                 String feedbackId,
                                 String conversationId,
                                 String userId,
                                 FeedbackRating rating,
                                 String reason,
                                 String assistantReply,
                                 String suggestedEvalType,
                                 FeedbackEvalCandidateStatus status,
                                 String reviewNote,
                                 long reviewedAt,
                                 long timestamp) {
        this(candidateId,
                feedbackId,
                conversationId,
                userId,
                rating,
                reason,
                assistantReply,
                suggestedEvalType,
                FeedbackIssueType.OTHER,
                status,
                reviewNote,
                reviewedAt,
                timestamp);
    }

    public FeedbackEvalCandidate(String candidateId,
                                 String feedbackId,
                                 String conversationId,
                                 String userId,
                                 FeedbackRating rating,
                                 String reason,
                                 String assistantReply,
                                 String suggestedEvalType,
                                 FeedbackIssueType issueType,
                                 FeedbackEvalCandidateStatus status,
                                 String reviewNote,
                                 long reviewedAt,
                                 long timestamp) {
        this.candidateId = candidateId;
        this.feedbackId = feedbackId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.rating = rating;
        this.reason = reason;
        this.assistantReply = assistantReply;
        this.suggestedEvalType = suggestedEvalType;
        this.issueType = issueType == null ? FeedbackIssueType.OTHER : issueType;
        this.status = status;
        this.reviewNote = reviewNote;
        this.reviewedAt = reviewedAt;
        this.timestamp = timestamp;
    }

    /**
     * 返回审核后的新对象。
     *
     * 当前类保持不可变：审核不是直接修改原对象字段，而是创建一个状态更新后的副本。
     * 这样在多线程或后续持久化时更容易控制对象状态。
     */
    public FeedbackEvalCandidate reviewedAs(FeedbackEvalCandidateStatus nextStatus,
                                            String note,
                                            long reviewTime) {
        return new FeedbackEvalCandidate(
                candidateId,
                feedbackId,
                conversationId,
                userId,
                rating,
                reason,
                assistantReply,
                suggestedEvalType,
                issueType,
                nextStatus,
                note == null ? "" : note.trim(),
                reviewTime,
                timestamp
        );
    }

    public String getCandidateId() {
        return candidateId;
    }

    public String getFeedbackId() {
        return feedbackId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public FeedbackRating getRating() {
        return rating;
    }

    public String getReason() {
        return reason;
    }

    public String getAssistantReply() {
        return assistantReply;
    }

    public String getSuggestedEvalType() {
        return suggestedEvalType;
    }

    public FeedbackIssueType getIssueType() {
        return issueType;
    }

    public FeedbackEvalCandidateStatus getStatus() {
        return status;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public long getReviewedAt() {
        return reviewedAt;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
