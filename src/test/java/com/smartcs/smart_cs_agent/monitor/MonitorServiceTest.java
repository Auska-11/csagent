package com.smartcs.smart_cs_agent.monitor;

import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MonitorService 单元测试。
 *
 * 这里不启动 Spring 容器，而是直接构造 TraceEvent。
 * 这样可以专注验证“Trace -> Monitor 指标”的转换逻辑。
 */
class MonitorServiceTest {

    @Test
    void shouldAggregateMetricsFromTraceEvents() {
        MonitorService service = new MonitorService();

        ChatResponse response = new ChatResponse(
                "conv-1",
                "USER-001",
                "ok",
                List.of(
                        new TraceEvent("tool_observed", 1, "订单工具成功", Map.of(
                                "success", true,
                                "durationMs", 20
                        )),
                        new TraceEvent("tool_observed", 2, "退货工具失败", Map.of(
                                "success", false,
                                "durationMs", 10
                        )),
                        new TraceEvent("llm_called", 3, "调用 LLM", Map.of()),
                        new TraceEvent("llm_failed", 3, "LLM 失败", Map.of(
                                "durationMs", 100
                        )),
                        new TraceEvent("llm_observed", 3, "LLM 降级结果", Map.of(
                                "durationMs", 120
                        )),
                        new TraceEvent("handoff_requested", 4, "请求转人工", Map.of()),
                        new TraceEvent("handoff_created", 4, "创建人工工单", Map.of(
                                "success", true,
                                "durationMs", 5
                        ))
                )
        );

        service.recordTurn(response);

        MonitorSummary summary = service.getSummary();
        assertEquals(1, summary.getTotalTurns());
        assertEquals(3, summary.getToolCallCount());
        assertEquals(2, summary.getSuccessfulToolCallCount());
        assertEquals(1, summary.getFailedToolCallCount());
        assertEquals(0.6667, summary.getToolSuccessRate());
        assertEquals(1, summary.getLlmCallCount());
        assertEquals(1, summary.getLlmFailureCount());
        assertEquals(1.0, summary.getLlmFailureRate());
        assertEquals(1, summary.getHandoffCount());
        assertEquals(11, summary.getAverageToolDurationMs());
        assertEquals(120, summary.getAverageLlmDurationMs());
        assertEquals(1, summary.getRecentTurns().size());
        assertEquals("conv-1", summary.getRecentTurns().get(0).getConversationId());
    }

    @Test
    void shouldKeepOnlyRecentTenTurns() {
        MonitorService service = new MonitorService();

        for (int i = 0; i < 12; i++) {
            service.recordTurn(new ChatResponse("conv-" + i, "USER-001", "ok", List.of()));
        }

        MonitorSummary summary = service.getSummary();
        assertEquals(12, summary.getTotalTurns());
        assertEquals(10, summary.getRecentTurns().size());
        assertEquals("conv-11", summary.getRecentTurns().get(0).getConversationId());
        assertEquals("conv-2", summary.getRecentTurns().get(9).getConversationId());
    }
}
