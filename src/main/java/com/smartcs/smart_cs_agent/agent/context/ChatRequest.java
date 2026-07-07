package com.smartcs.smart_cs_agent.agent.context;

/**
 * 当前一轮聊天请求
 *
 * 【为什么要有 ChatRequest？】
 * 之前我们把“当前用户消息”和“跨轮会话状态”都放在 ConversationState 里。
 * 这样短期能跑，但概念容易混在一起。
 *
 * ChatRequest 只表示“这一次用户发来的请求”，它不负责保存历史状态。
 *
 * 【和 ConversationState 的区别】
 * - ChatRequest：当前这一轮输入，例如用户刚刚说了什么
 * - ConversationState：跨轮保存的状态，例如当前意图、任务槽位、历史消息
 *
 * 后续引入 TurnContext 时，会把 ChatRequest 和 ConversationState 组合起来，
 * 表示“一轮 Agent 执行上下文”。
 */
public class ChatRequest {

    /** 会话 ID：用于区分不同聊天窗口或不同任务 */
    private final String conversationId;

    /** 用户 ID：用于识别当前用户身份 */
    private final String userId;

    /** 当前这一轮用户输入的消息 */
    private final String message;

    public ChatRequest(String conversationId, String userId, String message) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.message = message;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getMessage() {
        return message;
    }
}
