package com.smartcs.smart_cs_agent.controller;

/**
 * 记忆清除接口的响应体。
 *
 * 【为什么不用 Map？】
 * 清除记忆属于比较敏感的操作，接口返回结构应该稳定清晰：
 * - 清除了什么范围
 * - 目标 ID 是什么
 * - 本次是否真的清到了已有记忆
 */
public class MemoryClearResponse {

    /** 清除范围：conversation 或 user */
    private final String scope;

    /** 被清除的目标 ID，例如 conversationId 或 userId */
    private final String targetId;

    /** true 表示清除前确实存在记忆；false 表示原本就没有 */
    private final boolean cleared;

    /** 给前端和调试面板看的简短说明 */
    private final String message;

    /** 操作时间，毫秒时间戳 */
    private final long timestamp;

    public MemoryClearResponse(String scope, String targetId, boolean cleared, String message, long timestamp) {
        this.scope = scope;
        this.targetId = targetId;
        this.cleared = cleared;
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getScope() {
        return scope;
    }

    public String getTargetId() {
        return targetId;
    }

    public boolean isCleared() {
        return cleared;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
