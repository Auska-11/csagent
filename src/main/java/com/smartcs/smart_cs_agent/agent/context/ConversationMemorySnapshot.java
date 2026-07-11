package com.smartcs.smart_cs_agent.agent.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话记忆快照。
 *
 * 【为什么单独建这个类？】
 * ConversationState 是 Agent 内部真正使用的状态对象，里面可能会有一些临时字段。
 * 前端和接口不应该直接暴露整个 ConversationState，否则后续内部结构一改，接口也会被迫跟着改。
 *
 * 所以这里做一个只读快照：
 * - 给前端 Memory 面板展示
 * - 给面试演示说明“三级记忆”
 * - 给测试验证记忆是否被正确更新
 */
public class ConversationMemorySnapshot {

    /** 当前会话 ID，用来确认这份记忆属于哪个聊天窗口 */
    private final String conversationId;

    /** 当前用户 ID，用来确认这份记忆属于哪个用户 */
    private final String userId;

    /** 当前正在处理的意图，例如 return_apply、order_query */
    private final String activeIntent;

    /** 当前等待用户补充的槽位，例如 order_id、return_reason */
    private final String pendingSlot;

    /** 当前正在执行或最近结束的新版 Workflow ID。 */
    private final String activeWorkflowId;

    /** 当前 Workflow 实现版本。 */
    private final String workflowVersion;

    /** ACTIVE、WAITING_USER、COMPLETED 等生命周期状态。 */
    private final String workflowStatus;

    /** 只展示当前 Workflow 命名空间内的业务状态。 */
    private final Map<String, Object> workflowState;

    /** Workflow 首次启动时间。 */
    private final long workflowStartedAt;

    /** Workflow 最近状态更新时间。 */
    private final long workflowUpdatedAt;

    /** 任务记忆：保存当前任务已经收集到的槽位和关键状态 */
    private final Map<String, Object> taskMemory;

    /** 短期记忆：最近几条用户/助手消息，用来保持当前上下文 */
    private final List<Map<String, String>> shortTermMessages;

    /** 摘要记忆：长会话被压缩后的摘要，避免 prompt 无限变长 */
    private final String summaryMemory;

    /** 当前会话累计写入过多少条消息，包括用户消息和助手消息 */
    private final int totalMessageCount;

    /** 当前短期记忆最多保留多少条消息 */
    private final int shortTermMessageLimit;

    /** 摘要更新次数。这个数字变大，说明发生过记忆压缩。 */
    private final int summaryVersion;

    /** 摘要最近一次更新时间，毫秒时间戳；没有摘要时为 0 */
    private final long summaryUpdatedAt;

    public ConversationMemorySnapshot(String conversationId,
                                      String userId,
                                      String activeIntent,
                                      String pendingSlot,
                                      String activeWorkflowId,
                                      String workflowVersion,
                                      String workflowStatus,
                                      Map<String, Object> workflowState,
                                      long workflowStartedAt,
                                      long workflowUpdatedAt,
                                      Map<String, Object> taskMemory,
                                      List<Map<String, String>> shortTermMessages,
                                      String summaryMemory,
                                      int totalMessageCount,
                                      int shortTermMessageLimit,
                                      int summaryVersion,
                                      long summaryUpdatedAt) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.activeIntent = activeIntent;
        this.pendingSlot = pendingSlot;
        this.activeWorkflowId = activeWorkflowId;
        this.workflowVersion = workflowVersion;
        this.workflowStatus = workflowStatus;
        this.workflowState = workflowState == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(workflowState));
        this.workflowStartedAt = workflowStartedAt;
        this.workflowUpdatedAt = workflowUpdatedAt;
        this.taskMemory = taskMemory == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(taskMemory));
        this.shortTermMessages = shortTermMessages == null ? List.of() : List.copyOf(shortTermMessages);
        this.summaryMemory = summaryMemory == null ? "" : summaryMemory;
        this.totalMessageCount = totalMessageCount;
        this.shortTermMessageLimit = shortTermMessageLimit;
        this.summaryVersion = summaryVersion;
        this.summaryUpdatedAt = summaryUpdatedAt;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getActiveIntent() {
        return activeIntent;
    }

    public String getPendingSlot() {
        return pendingSlot;
    }

    public String getActiveWorkflowId() {
        return activeWorkflowId;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public String getWorkflowStatus() {
        return workflowStatus;
    }

    public Map<String, Object> getWorkflowState() {
        return workflowState;
    }

    public long getWorkflowStartedAt() {
        return workflowStartedAt;
    }

    public long getWorkflowUpdatedAt() {
        return workflowUpdatedAt;
    }

    public Map<String, Object> getTaskMemory() {
        return taskMemory;
    }

    public List<Map<String, String>> getShortTermMessages() {
        return shortTermMessages;
    }

    public String getSummaryMemory() {
        return summaryMemory;
    }

    public int getTotalMessageCount() {
        return totalMessageCount;
    }

    public int getShortTermMessageLimit() {
        return shortTermMessageLimit;
    }

    public int getSummaryVersion() {
        return summaryVersion;
    }

    public long getSummaryUpdatedAt() {
        return summaryUpdatedAt;
    }
}
