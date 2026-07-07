package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.MonitorSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldRecordFeedback() {
        MonitorService service = new MonitorService();
        MonitorController controller = new MonitorController(service);
        FeedbackRequest request = new FeedbackRequest(
                "conv-1",
                "USER-001",
                "HELPFUL",
                "很清楚",
                "您的订单已经发货"
        );

        FeedbackResponse response = controller.recordFeedback(request);
        MonitorSummary summary = controller.getSummary();

        assertTrue(response.isSuccess());
        assertEquals("FDB-000001", response.getFeedback().getFeedbackId());
        assertEquals("HELPFUL", response.getFeedback().getRating().name());
        assertEquals("您的订单已经发货", response.getFeedback().getAssistantReply());
        assertEquals(1, summary.getFeedbackCount());
        assertEquals(1.0, summary.getHelpfulFeedbackRate());
    }

    @Test
    void shouldReturnFeedbackEvalCandidates() {
        MonitorService service = new MonitorService();
        MonitorController controller = new MonitorController(service);
        FeedbackRequest request = new FeedbackRequest(
                "conv-1",
                "USER-001",
                "UNHELPFUL",
                "没有说明退货时效",
                "可以为您申请退货"
        );

        controller.recordFeedback(request);

        assertEquals(1, controller.getFeedbackEvalCandidates().size());
        assertEquals("EVAL-CAND-000001", controller.getFeedbackEvalCandidates().get(0).getCandidateId());
        assertEquals("可以为您申请退货", controller.getFeedbackEvalCandidates().get(0).getAssistantReply());
    }

    @Test
    void shouldRejectInvalidFeedbackRating() {
        MonitorController controller = new MonitorController(new MonitorService());
        FeedbackRequest request = new FeedbackRequest("conv-1", "USER-001", "LIKE", "");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> controller.recordFeedback(request)
        );

        assertEquals("Rating must be HELPFUL or UNHELPFUL", exception.getMessage());
    }
}
