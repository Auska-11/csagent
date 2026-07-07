package com.smartcs.smart_cs_agent.monitor;

/**
 * 单个工具的监控统计。
 *
 * 【为什么要按工具拆开？】
 * 只看整体工具成功率时，我们知道“工具层有没有问题”，
 * 但不知道到底是订单工具、知识库工具，还是人工转接工具出了问题。
 *
 * 这个 DTO 让 Monitor 面板可以按工具名展示成功率和耗时，
 * 面试时也更容易讲清楚如何定位 Agent 工具调用问题。
 */
public class ToolMonitorSnapshot {

    /** 工具名称，例如 order_lookup、knowledge_search */
    private final String toolName;

    /** 累计调用次数 */
    private final long callCount;

    /** 成功调用次数 */
    private final long successfulCallCount;

    /** 失败调用次数 */
    private final long failedCallCount;

    /** 成功率，范围 0 到 1 */
    private final double successRate;

    /** 平均耗时，单位毫秒 */
    private final long averageDurationMs;

    public ToolMonitorSnapshot(String toolName,
                               long callCount,
                               long successfulCallCount,
                               long failedCallCount,
                               double successRate,
                               long averageDurationMs) {
        this.toolName = toolName;
        this.callCount = callCount;
        this.successfulCallCount = successfulCallCount;
        this.failedCallCount = failedCallCount;
        this.successRate = successRate;
        this.averageDurationMs = averageDurationMs;
    }

    public String getToolName() {
        return toolName;
    }

    public long getCallCount() {
        return callCount;
    }

    public long getSuccessfulCallCount() {
        return successfulCallCount;
    }

    public long getFailedCallCount() {
        return failedCallCount;
    }

    public double getSuccessRate() {
        return successRate;
    }

    public long getAverageDurationMs() {
        return averageDurationMs;
    }
}
