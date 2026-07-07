package com.smartcs.smart_cs_agent.monitor;

/**
 * 单条用户反馈记录。
 *
 * 【第一版为什么只做内存记录？】
 * 当前项目重点是把“反馈闭环”的链路跑通：
 * 前端提交反馈 -> 后端记录 -> Monitor 聚合 -> 面板展示。
 * 真正上线时可以把这个类保存到数据库或日志系统，Controller 和前端接口不需要大改。
 */
public class FeedbackRecord {

    /** 反馈 ID，方便前端和日志定位某一次反馈 */
    private final String feedbackId;

    /** 用户评价的是哪一个会话里的回复 */
    private final String conversationId;

    /** 哪个用户提交的反馈 */
    private final String userId;

    /** HELPFUL 或 UNHELPFUL */
    private final FeedbackRating rating;

    /** 用户可选填写的原因，第一版前端先传空字符串也可以 */
    private final String reason;

    /** 被评价的助手回复内容，方便后续分析“用户为什么觉得这次回答不好” */
    private final String assistantReply;

    /** 反馈提交时间，毫秒时间戳 */
    private final long timestamp;

    public FeedbackRecord(String feedbackId,
                          String conversationId,
                          String userId,
                          FeedbackRating rating,
                          String reason,
                          long timestamp) {
        this(feedbackId, conversationId, userId, rating, reason, "", timestamp);
    }

    public FeedbackRecord(String feedbackId,
                          String conversationId,
                          String userId,
                          FeedbackRating rating,
                          String reason,
                          String assistantReply,
                          long timestamp) {
        this.feedbackId = feedbackId;
        this.conversationId = conversationId;
        this.userId = userId;
        this.rating = rating;
        this.reason = reason;
        this.assistantReply = assistantReply;
        this.timestamp = timestamp;
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

    public long getTimestamp() {
        return timestamp;
    }
}
