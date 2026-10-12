package com.smartcs.smart_cs_agent.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 聊天接口的 HTTP 请求体。
 *
 * 【为什么不直接复用 ChatRequest？】
 * ChatApiRequest 属于 Controller 层，表示“前端传来的 JSON 长什么样”。
 * ChatRequest 属于 Agent 层，表示“Agent 本轮执行需要什么输入”。
 *
 * 这两个对象字段现在很像，但职责不同：
 * - ChatApiRequest：面向 HTTP 接口，后续可以加参数校验、兼容旧字段等。
 * - ChatRequest：面向 Agent 内部执行，应该保持干净稳定。
 *
 * 新手可以先简单理解为：
 * Controller DTO 负责接收外部请求，Agent Request 负责进入 Agent 主链路。
 */
public class ChatApiRequest {

    /**
     * 用户当前输入的消息。
     *
     * @NotBlank 会同时拦住 null、空字符串、全空格三种情况。
     * 这样 Controller 不需要手写太多重复判断，校验失败会交给 GlobalExceptionHandler 统一返回 400。
     */
    @NotBlank(message = "Message cannot be empty")
    @Size(max = 1000, message = "Message is too long")
    private String message;

    /**
     * 旧客户端的用户声明，只用于检查是否与 JWT Principal 一致。
     * 查询“我的订单”“我的信息”时，真正身份始终来自 Authorization Bearer JWT。
     */
    @Size(max = 64, message = "UserId is too long")
    private String userId;

    /** 会话 ID，用来让多轮对话接着同一个任务状态继续走。 */
    @Size(max = 128, message = "ConversationId is too long")
    private String conversationId;

    /** Stable channel event identity; scoped by authenticated tenant, user and conversation. */
    @Size(max = 128, message = "MessageId is too long")
    private String messageId;

    /**
     * 旧前端兼容字段。HTTP 认证已经统一改用 Authorization: Bearer JWT，服务端不再读取这里的值。
     */
    @Size(max = 128, message = "Token is too long")
    private String token;

    public ChatApiRequest() {
    }

    public ChatApiRequest(String message, String userId, String conversationId) {
        this.message = message;
        this.userId = userId;
        this.conversationId = conversationId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
}
