package com.smartcs.smart_cs_agent.monitor;

import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Agent 在线监控指标服务。
 *
 * 【为什么从 Trace 统计？】
 * 我们已经在 AgentRuntime 里记录了工具调用、LLM 调用、人工转接和耗时。
 * Monitor 第一版直接消费 Trace，不需要再侵入 Runtime。
 *
 * 这样做有两个好处：
 * 1. Runtime 继续专注“执行 Agent”，Monitor 专注“观察 Agent”。
 * 2. 面试时可以讲清楚：Trace 是底层事实日志，Monitor 是基于 Trace 的聚合视图。
 */
@Service
public class MonitorService {

    /** 最近只保留 10 轮摘要，避免内存无限增长 */
    private static final int RECENT_TURN_LIMIT = 10;

    private long totalTurns;
    private long toolCallCount;
    private long failedToolCallCount;
    private long llmCallCount;
    private long llmFailureCount;
    private long handoffCount;
    private long totalToolDurationMs;
    private long toolDurationSamples;
    private long totalLlmDurationMs;
    private long llmDurationSamples;
    private long lastUpdatedAt;

    private final Deque<MonitorTurnSnapshot> recentTurns = new ArrayDeque<>();

    /**
     * 记录一轮聊天响应。
     *
     * 注意：这个方法用 synchronized，是因为 Spring 单例 Service 可能被多个请求同时调用。
     * 第一版数据量很小，用 synchronized 足够清晰；后续接真实流量时可以改成 AtomicLong 或指标系统。
     */
    public synchronized void recordTurn(ChatResponse response) {
        if (response == null) {
            return;
        }

        TurnAccumulator turn = new TurnAccumulator();
        for (TraceEvent event : response.getTrace()) {
            collectEvent(event, turn);
        }

        totalTurns++;
        toolCallCount += turn.toolCalls;
        failedToolCallCount += turn.failedToolCalls;
        llmCallCount += turn.llmCalls;
        llmFailureCount += turn.llmFailures;
        handoffCount += turn.handoffCount;
        totalToolDurationMs += turn.toolDurationMs;
        toolDurationSamples += turn.toolDurationSamples;
        totalLlmDurationMs += turn.llmDurationMs;
        llmDurationSamples += turn.llmDurationSamples;
        lastUpdatedAt = System.currentTimeMillis();

        recentTurns.addFirst(new MonitorTurnSnapshot(
                response.getConversationId(),
                response.getUserId(),
                response.getTimestamp(),
                turn.toolCalls,
                turn.failedToolCalls,
                turn.llmCalls,
                turn.llmFailures,
                turn.handoffCount,
                turn.toolDurationMs + turn.llmDurationMs
        ));

        while (recentTurns.size() > RECENT_TURN_LIMIT) {
            recentTurns.removeLast();
        }
    }

    /**
     * 获取当前聚合指标。
     *
     * 这个对象会直接序列化成 JSON，供前端 Monitor 面板展示。
     */
    public synchronized MonitorSummary getSummary() {
        long successfulToolCallCount = Math.max(0, toolCallCount - failedToolCallCount);
        List<MonitorTurnSnapshot> recent = new ArrayList<>(recentTurns);

        return new MonitorSummary(
                totalTurns,
                toolCallCount,
                successfulToolCallCount,
                failedToolCallCount,
                rate(successfulToolCallCount, toolCallCount),
                llmCallCount,
                llmFailureCount,
                rate(llmFailureCount, llmCallCount),
                handoffCount,
                average(totalToolDurationMs, toolDurationSamples),
                average(totalLlmDurationMs, llmDurationSamples),
                lastUpdatedAt,
                recent
        );
    }

    private void collectEvent(TraceEvent event, TurnAccumulator turn) {
        if (event == null || event.getEventType() == null) {
            return;
        }

        String eventType = event.getEventType();
        Map<String, Object> data = event.getData();

        switch (eventType) {
            case "tool_observed" -> recordToolResult(data, turn, asBoolean(data.get("success"), true));
            case "tool_failed" -> recordToolResult(data, turn, false);
            case "handoff_created" -> {
                recordHandoff(turn);
                recordToolResult(data, turn, true);
            }
            case "handoff_failed" -> {
                recordHandoff(turn);
                recordToolResult(data, turn, false);
            }
            case "handoff_requested" -> recordHandoff(turn);
            case "llm_called" -> turn.llmCalls++;
            case "llm_failed" -> turn.llmFailures++;
            case "llm_observed" -> recordLlmDuration(data, turn);
            default -> {
                // 其他 Trace 事件暂时不参与 Monitor 聚合，例如 action_planned、final_answer。
            }
        }
    }

    private void recordToolResult(Map<String, Object> data, TurnAccumulator turn, boolean success) {
        turn.toolCalls++;
        if (!success) {
            turn.failedToolCalls++;
        }

        Long durationMs = asLong(data.get("durationMs"));
        if (durationMs != null) {
            turn.toolDurationMs += durationMs;
            turn.toolDurationSamples++;
        }
    }

    private void recordLlmDuration(Map<String, Object> data, TurnAccumulator turn) {
        Long durationMs = asLong(data.get("durationMs"));
        if (durationMs != null) {
            turn.llmDurationMs += durationMs;
            turn.llmDurationSamples++;
        }
    }

    private void recordHandoff(TurnAccumulator turn) {
        // 一轮 Trace 里通常会同时有 handoff_requested 和 handoff_created。
        // 这里用标记位保证同一轮只统计一次转人工。
        if (!turn.handoffRecorded) {
            turn.handoffCount++;
            turn.handoffRecorded = true;
        }
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private long average(long total, long count) {
        if (count <= 0) {
            return 0;
        }
        return total / count;
    }

    private double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        double value = numerator * 1.0 / denominator;
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /**
     * 单轮临时统计对象。
     *
     * 它只在 recordTurn 内部使用，不需要暴露给 Controller。
     */
    private static class TurnAccumulator {
        private long toolCalls;
        private long failedToolCalls;
        private long llmCalls;
        private long llmFailures;
        private long handoffCount;
        private long toolDurationMs;
        private long toolDurationSamples;
        private long llmDurationMs;
        private long llmDurationSamples;
        private boolean handoffRecorded;
    }
}
