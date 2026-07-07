package com.smartcs.smart_cs_agent.memory;

import java.util.List;

/**
 * 用户长期记忆快照。
 *
 * 【它和 ConversationMemorySnapshot 有什么区别？】
 * - ConversationMemorySnapshot：当前会话里的短期上下文和任务状态。
 * - UserMemorySnapshot：跨会话保留的用户偏好，例如喜欢什么商品类型。
 *
 * 第一版先只做商品偏好，因为它和客服 Agent 的“商品推荐”场景结合最自然。
 */
public class UserMemorySnapshot {

    /** 用户 ID */
    private final String userId;

    /** 用户偏好的商品关键词，例如“蓝牙耳机”“智能手表” */
    private final List<String> productPreferences;

    /** 记忆更新次数，方便前端和测试判断是否真的写入过长期记忆 */
    private final int version;

    /** 最近更新时间，毫秒时间戳 */
    private final long updatedAt;

    public UserMemorySnapshot(String userId,
                              List<String> productPreferences,
                              int version,
                              long updatedAt) {
        this.userId = userId;
        this.productPreferences = productPreferences == null ? List.of() : List.copyOf(productPreferences);
        this.version = version;
        this.updatedAt = updatedAt;
    }

    public String getUserId() {
        return userId;
    }

    public List<String> getProductPreferences() {
        return productPreferences;
    }

    public int getVersion() {
        return version;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasProductPreferences() {
        return !productPreferences.isEmpty();
    }
}
