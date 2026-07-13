package com.smartcs.smart_cs_agent.agent.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ConversationState 的稳定持久化格式。
 *
 * <p>不要直接把 ConversationState 交给 Jackson：运行时状态中包含 Observation、Provider 结果等
 * 多态对象，反序列化后很容易变成普通 Map，Workflow 再做 instanceof 时就会失败。这个快照只
 * 保存真正需要跨轮恢复的字符串状态和消息窗口。</p>
 */
public record ConversationStateSnapshot(
        int schemaVersion,
        String userId,
        String message,
        List<Map<String, String>> messages,
        String activeIntent,
        Map<String, String> taskState,
        String summaryMemory,
        int totalMessageCount,
        int summaryVersion,
        long summaryUpdatedAt,
        String activeWorkflowId,
        String workflowVersion,
        String workflowStatus,
        long workflowStartedAt,
        long workflowUpdatedAt,
        Map<String, WorkflowStateSnapshot> workflowStates
) {

    /** 当前代码能够读取和写出的快照版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ConversationStateSnapshot {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("snapshot userId cannot be empty");
        }
        userId = userId.trim();
        message = message == null ? "" : message;
        messages = immutableMessages(messages);
        taskState = immutableStringMap(taskState);
        summaryMemory = summaryMemory == null ? "" : summaryMemory;
        workflowStates = immutableWorkflowStates(workflowStates);
        if (totalMessageCount < messages.size()) {
            throw new IllegalArgumentException("totalMessageCount cannot be smaller than message window");
        }
        if (summaryVersion < 0
                || summaryUpdatedAt < 0
                || workflowStartedAt < 0
                || workflowUpdatedAt < 0) {
            throw new IllegalArgumentException("persisted counters and timestamps cannot be negative");
        }
    }

    private static List<Map<String, String>> immutableMessages(List<Map<String, String>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> copy = new ArrayList<>();
        for (Map<String, String> message : source) {
            Map<String, String> safeMessage = immutableStringMap(message);
            if (!safeMessage.containsKey("role") || !safeMessage.containsKey("content")) {
                throw new IllegalArgumentException("persisted message must contain role and content");
            }
            copy.add(safeMessage);
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("persisted string map cannot contain null");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, WorkflowStateSnapshot> immutableWorkflowStates(
            Map<String, WorkflowStateSnapshot> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, WorkflowStateSnapshot> copy = new LinkedHashMap<>();
        for (Map.Entry<String, WorkflowStateSnapshot> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                throw new IllegalArgumentException("workflowStates cannot contain empty entries");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
