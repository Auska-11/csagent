package com.smartcs.smart_cs_agent.channel.xianyu;

/**
 * 闲鱼会话被卖家接管、机器人暂停自动回复期间，买家消息不应进入 Agent。
 * 返回 409 让侧车知道当前是人工模式（与 CONVERSATION_BUSY 同一语义层：调用方稍后重试
 * 或走人工链路），而不是把消息静默丢掉或伪装成 500。
 */
public class XianyuConversationPausedException extends RuntimeException {

    public XianyuConversationPausedException(String conversationId) {
        super("Xianyu conversation is in manual mode: " + conversationId);
    }
}
