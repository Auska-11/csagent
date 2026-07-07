package com.smartcs.smart_cs_agent.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户反馈接口请求体。
 *
 * 【为什么反馈也要有 DTO？】
 * 反馈来自前端，属于外部输入，也需要独立校验。
 * 如果直接把前端 JSON 传进 MonitorService，后续字段变化会污染监控层。
 */
public class FeedbackRequest {

    /** 当前反馈对应的会话 ID，用来把“用户评价”关联回某一轮对话上下文 */
    @NotBlank(message = "ConversationId cannot be empty")
    @Size(max = 128, message = "ConversationId is too long")
    private String conversationId;

    /** 提交反馈的用户 ID */
    @Size(max = 64, message = "UserId is too long")
    private String userId;

    /** 反馈评分：HELPFUL 或 UNHELPFUL */
    @NotBlank(message = "Rating cannot be empty")
    @Size(max = 32, message = "Rating is too long")
    private String rating;

    /** 可选反馈原因，第一版先作为调试和面试演示字段 */
    @Size(max = 300, message = "Reason is too long")
    private String reason;

    /**
     * 被评价的助手回复内容。
     *
     * 这个字段不是为了让 Monitor 重新理解业务，而是为了后续生成 Eval 候选样本时，
     * 能看到“用户到底对哪段回复点了无用”。
     */
    @Size(max = 2000, message = "Assistant reply is too long")
    private String assistantReply;

    public FeedbackRequest() {
    }

    public FeedbackRequest(String conversationId, String userId, String rating, String reason) {
        this(conversationId, userId, rating, reason, "");
    }

    public FeedbackRequest(String conversationId,
                           String userId,
                           String rating,
                           String reason,
                           String assistantReply) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.rating = rating;
        this.reason = reason;
        this.assistantReply = assistantReply;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRating() {
        return rating;
    }

    public void setRating(String rating) {
        this.rating = rating;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getAssistantReply() {
        return assistantReply;
    }

    public void setAssistantReply(String assistantReply) {
        this.assistantReply = assistantReply;
    }
}
