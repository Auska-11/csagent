package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 人工转接工单 DTO。
 *
 * 真实项目里这里可以对应工单系统、IM 系统或 CRM 系统的工单对象。
 */
public class HandoffTicketData {

    private final String ticketId;
    private final String userId;
    private final String conversationId;
    private final String reason;
    private final String summary;
    private final String status;
    private final String createdAt;

    public HandoffTicketData(String ticketId,
                             String userId,
                             String conversationId,
                             String reason,
                             String summary,
                             String status,
                             String createdAt) {
        this.ticketId = ticketId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.reason = reason;
        this.summary = summary;
        this.status = status;
        this.createdAt = createdAt;
    }

    @JsonProperty("ticket_id")
    public String getTicketId() {
        return ticketId;
    }

    @JsonProperty("user_id")
    public String getUserId() {
        return userId;
    }

    @JsonProperty("conversation_id")
    public String getConversationId() {
        return conversationId;
    }

    @JsonProperty("reason")
    public String getReason() {
        return reason;
    }

    @JsonProperty("summary")
    public String getSummary() {
        return summary;
    }

    @JsonProperty("status")
    public String getStatus() {
        return status;
    }

    @JsonProperty("created_at")
    public String getCreatedAt() {
        return createdAt;
    }
}
