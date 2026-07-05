package com.smartcs.smart_cs_agent.agent.context;

import java.util.*;

/**
 * 会话状态
 *
 * 【什么是 ConversationState？】
 * ConversationState 是一轮对话的状态记录本。
 * 它记录了：用户说了什么、Agent 做了什么、当前执行到哪一步。
 *
 * 【存储内容】
 * - message：当前用户消息
 * - messages：历史对话记录
 * - activeIntent：当前识别的意图
 * - observations：执行过程中的观察结果
 * - taskState：任务状态（存储临时数据）
 * - userId：用户标识
 *
 * 【生命周期】
 * 用户消息 → 创建状态 → 更新状态 → 保存/丢弃
 *
 * 【与 Memory 的区别】
 * - ConversationState：一轮对话，会话结束可能丢弃
 * - Memory：跨会话，持久化存储
 *
 * 【后续扩展】
 * Phase 6 做 Memory 时，可以注入用户画像到 ConversationState。
 */
public class ConversationState {

    /** 当前用户消息 */
    private final String message;

    /** 历史对话记录 */
    private final List<Map<String, String>> messages;

    /** 当前识别的意图 */
    private String activeIntent;

    /** 执行过程中的观察结果 */
    private final List<Observation> observations;

    /** 任务状态（存储临时数据） */
    private final Map<String, Object> taskState;

    /** 用户标识 */
    private final String userId;

    /**
     * 构造函数
     *
     * @param message 用户消息
     */
    public ConversationState(String message) {
        this(message, "default");
    }

    /**
     * 构造函数
     *
     * @param message 用户消息
     * @param userId 用户标识
     */
    public ConversationState(String message, String userId) {
        this.message = message;
        this.userId = userId;
        this.messages = new ArrayList<>();
        this.observations = new ArrayList<>();
        this.taskState = new HashMap<>();

        // 把用户消息加入历史记录
        this.messages.add(Map.of("role", "user", "content", message));
    }

    /**
     * 获取用户消息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 获取历史对话记录
     */
    public List<Map<String, String>> getMessages() {
        return messages;
    }

    /**
     * 添加助手消息到历史记录
     */
    public void addAssistantMessage(String content) {
        messages.add(Map.of("role", "assistant", "content", content));
    }

    /**
     * 获取当前识别的意图
     */
    public String getActiveIntent() {
        return activeIntent;
    }

    /**
     * 设置当前识别的意图
     */
    public void setActiveIntent(String activeIntent) {
        this.activeIntent = activeIntent;
    }

    /**
     * 添加观察结果
     *
     * @param type 观察类型（capability, llm_response 等）
     * @param data 观察数据
     */
    public void addObservation(String type, Object data) {
        observations.add(new Observation(type, data));
    }

    /**
     * 获取所有观察结果
     */
    public List<Observation> getObservations() {
        return observations;
    }

    /**
     * 获取最新的观察结果
     */
    public Observation getLatestObservation() {
        if (observations.isEmpty()) {
            return null;
        }
        return observations.get(observations.size() - 1);
    }

    /**
     * 获取指定类型的最新观察结果
     */
    public Observation getLatestObservationByType(String type) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            if (observations.get(i).getType().equals(type)) {
                return observations.get(i);
            }
        }
        return null;
    }

    /**
     * 设置任务状态
     */
    public void setTaskState(String key, Object value) {
        taskState.put(key, value);
    }

    /**
     * 获取任务状态
     */
    public Object getTaskState(String key) {
        return taskState.get(key);
    }

    /**
     * 获取用户标识
     */
    public String getUserId() {
        return userId;
    }

    @Override
    public String toString() {
        return "ConversationState{" +
                "message=" + message +
                ", activeIntent=" + activeIntent +
                ", observations=" + observations.size() +
                ", userId=" + userId +
                "}";
    }
}