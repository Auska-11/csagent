package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.MonitorSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MonitorController 单元测试。
 *
 * 直接实例化 Controller，可以快速验证接口层是否正确调用 MonitorService。
 */
class MonitorControllerTest {

    @Test
    void shouldReturnMonitorSummary() {
        MonitorService service = new MonitorService();
        service.recordTurn(new ChatResponse(
                "conv-1",
                "USER-001",
                "ok",
                List.of(new TraceEvent("tool_observed", 1, "工具成功", Map.of(
                        "success", true,
                        "durationMs", 15
                )))
        ));

        MonitorController controller = new MonitorController(service);

        MonitorSummary summary = controller.getSummary();
        assertEquals(1, summary.getTotalTurns());
        assertEquals(1, summary.getToolCallCount());
        assertEquals(1, summary.getSuccessfulToolCallCount());
    }
}
