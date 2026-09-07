package com.smartcs.smart_cs_agent.agent.context;

/**
 * 同一会话的上一轮处理尚未结束，本次请求排队等待超出请求预算时快速失败。
 *
 * <p>语义上这是“暂时忙，稍后重试”（HTTP 409/CONVERSATION_BUSY），不是服务器内部错误；
 * 抛出时不会执行任何 Workflow 或 Capability，天然无副作用。</p>
 */
public class ConversationBusyException extends RuntimeException {

    private final ConversationKey conversationKey;

    public ConversationBusyException(ConversationKey conversationKey) {
        super("Conversation is currently processing another message, please retry later");
        this.conversationKey = conversationKey;
    }

    public ConversationKey getConversationKey() {
        return conversationKey;
    }
}
