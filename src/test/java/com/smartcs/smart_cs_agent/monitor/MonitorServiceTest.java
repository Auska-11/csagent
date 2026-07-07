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
                                "capability", "order_lookup",
                                "success", true,
                                "durationMs", 20
                        )),
                        new TraceEvent("tool_observed", 2, "退货工具失败", Map.of(
                                "capability", "return_apply",
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
        assertEquals(3, summary.getToolStats().size());
        assertEquals(0, summary.getPendingEvalCandidateCount());
        assertEquals(0, summary.getApprovedEvalCandidateCount());
        assertEquals(0, summary.getRejectedEvalCandidateCount());
        assertEquals(1.0, findTool(summary, "order_lookup").getSuccessRate());
        assertEquals(0.0, findTool(summary, "return_apply").getSuccessRate());
        assertEquals(5, findTool(summary, "handoff_create").getAverageDurationMs());
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

    @Test
    void shouldAggregateUserFeedback() {
        MonitorService service = new MonitorService();

        FeedbackRecord helpful = service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.HELPFUL,
                "回答解决了我的问题"
        );
        FeedbackRecord unhelpful = service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "没有说明退货时效"
        );

        MonitorSummary summary = service.getSummary();
        assertEquals("FDB-000001", helpful.getFeedbackId());
        assertEquals("FDB-000002", unhelpful.getFeedbackId());
        assertEquals(2, summary.getFeedbackCount());
        assertEquals(1, summary.getHelpfulFeedbackCount());
        assertEquals(1, summary.getUnhelpfulFeedbackCount());
        assertEquals(0.5, summary.getHelpfulFeedbackRate());
        assertEquals(1, summary.getEvalCandidateCount());
        assertEquals(2, summary.getRecentFeedbacks().size());
        assertEquals(FeedbackRating.UNHELPFUL, summary.getRecentFeedbacks().get(0).getRating());
        assertEquals(1, summary.getPendingEvalCandidateCount());
        assertEquals(0, summary.getApprovedEvalCandidateCount());
        assertEquals(0, summary.getRejectedEvalCandidateCount());
    }

    @Test
    void shouldCreateEvalCandidateFromUnhelpfulFeedback() {
        MonitorService service = new MonitorService();

        service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.HELPFUL,
                "回答清楚",
                "您的订单已经发货"
        );
        service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "没有说明退货时效",
                "可以为您申请退货"
        );

        MonitorSummary summary = service.getSummary();
        FeedbackEvalCandidate candidate = service.getFeedbackEvalCandidates().get(0);

        assertEquals(1, summary.getEvalCandidateCount());
        assertEquals(1, summary.getRecentEvalCandidates().size());
        assertEquals(1, summary.getPendingEvalCandidateCount());
        assertEquals("EVAL-CAND-000001", candidate.getCandidateId());
        assertEquals("FDB-000002", candidate.getFeedbackId());
        assertEquals("response_quality_candidate", candidate.getSuggestedEvalType());
        assertEquals(FeedbackEvalCandidateStatus.PENDING_REVIEW, candidate.getStatus());
        assertEquals("没有说明退货时效", candidate.getReason());
        assertEquals("可以为您申请退货", candidate.getAssistantReply());
    }

    @Test
    void shouldReviewEvalCandidate() {
        MonitorService service = new MonitorService();
        service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "回答缺少关键信息",
                "可以为您处理"
        );

        FeedbackEvalCandidate reviewed = service.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000001",
                FeedbackEvalCandidateStatus.APPROVED,
                "信息完整，可以补进回复质量评测"
        );

        assertEquals(FeedbackEvalCandidateStatus.APPROVED, reviewed.getStatus());
        assertEquals("信息完整，可以补进回复质量评测", reviewed.getReviewNote());
        assertEquals(reviewed.getStatus(), service.getFeedbackEvalCandidates().get(0).getStatus());
        assertEquals(0, service.getSummary().getPendingEvalCandidateCount());
        assertEquals(1, service.getSummary().getApprovedEvalCandidateCount());
        assertEquals(0, service.getSummary().getRejectedEvalCandidateCount());
    }

    @Test
    void shouldExportOnlyApprovedEvalCaseDrafts() {
        MonitorService service = new MonitorService();
        service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "回答缺少退货时效",
                "可以为您申请退货"
        );
        service.recordFeedback(
                "conv-2",
                "USER-002",
                FeedbackRating.UNHELPFUL,
                "用户误点，不适合作为样本",
                "您的订单已经发货"
        );

        service.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000001",
                FeedbackEvalCandidateStatus.APPROVED,
                "可以作为负例"
        );
        service.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000002",
                FeedbackEvalCandidateStatus.REJECTED,
                "误点，不导出"
        );

        List<FeedbackEvalCaseDraft> drafts = service.exportApprovedEvalCaseDrafts();

        assertEquals(1, drafts.size());
        assertEquals("feedback_eval_cand_000001", drafts.get(0).getId());
        assertEquals("EVAL-CAND-000001", drafts.get(0).getSourceCandidateId());
        assertEquals("可以为您申请退货", drafts.get(0).getCandidateReply());
        assertEquals(false, drafts.get(0).isExpectedPass());
        assertEquals("可以作为负例", drafts.get(0).getReviewNote());
        assertEquals(0, service.getSummary().getPendingEvalCandidateCount());
        assertEquals(1, service.getSummary().getApprovedEvalCandidateCount());
        assertEquals(1, service.getSummary().getRejectedEvalCandidateCount());
    }

    private ToolMonitorSnapshot findTool(MonitorSummary summary, String toolName) {
        return summary.getToolStats()
                .stream()
                .filter(tool -> tool.getToolName().equals(toolName))
                .findFirst()
                .orElseThrow();
    }
}
