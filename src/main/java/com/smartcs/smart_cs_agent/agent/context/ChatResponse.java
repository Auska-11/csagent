package com.smartcs.smart_cs_agent.agent.context;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 聊天接口响应对象。
 *
 * 【为什么不继续直接返回 String？】
 * 只返回 String 时，前端只能拿到最终回复。
 * 现在我们希望同时返回：
 * - conversationId：下一轮继续同一个会话
 * - userId：确认当前用户
 * - reply：最终回复
 * - trace：本轮 Agent 执行链路
 *
 * 所以用 ChatResponse 把这些字段统一包装起来。
 */
public class ChatResponse {

    /** 会话 ID：前端下一轮请求可以继续带回来 */
    private final String conversationId;

    /** 用户 ID：用于确认当前响应属于哪个用户 */
    private final String userId;

    /** Agent 最终回复给用户的话 */
    private final String reply;

    /** 响应时间，毫秒时间戳 */
    private final long timestamp;

    /** 本轮执行轨迹，用于调试和面试演示 */
    private final List<TraceEvent> trace;

    /**
     * 本轮是否成功转人工（Workflow 终态 HANDED_OFF）。
     *
     * 渠道适配层（如闲鱼侧车）据此对该会话暂停自动回复，人工接管后由运营侧恢复；
     * 这是双层转人工的业务层标志位（docs-76 §5.2 设计点 4）。
     */
    private final boolean handoffRequested;

    public ChatResponse(String conversationId, String userId, String reply, List<TraceEvent> trace) {
        this(conversationId, userId, reply, trace, false);
    }

    public ChatResponse(String conversationId,
                        String userId,
                        String reply,
                        List<TraceEvent> trace,
                        boolean handoffRequested) {
        this(conversationId, userId, reply, trace, handoffRequested, System.currentTimeMillis());
    }

    @JsonCreator
    public ChatResponse(@JsonProperty("conversationId") String conversationId,
                        @JsonProperty("userId") String userId,
                        @JsonProperty("reply") String reply,
                        @JsonProperty("trace") List<TraceEvent> trace,
                        @JsonProperty("handoffRequested") boolean handoffRequested,
                        @JsonProperty("timestamp") long timestamp) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.reply = reply;
        this.timestamp = timestamp;
        this.trace = trace == null ? List.of() : List.copyOf(trace);
        this.handoffRequested = handoffRequested;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getReply() {
        return reply;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public List<TraceEvent> getTrace() {
        return trace;
    }

    public boolean isHandoffRequested() {
        return handoffRequested;
    }
}
