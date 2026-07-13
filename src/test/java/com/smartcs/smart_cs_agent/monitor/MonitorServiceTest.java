package com.smartcs.smart_cs_agent.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MonitorService 单元测试。
 *
 * 这里不启动 Spring 容器，而是直接构造 TraceEvent。
 * 这样可以专注验证“Trace -> Monitor 指标”的转换逻辑。
 */
class MonitorServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAggregateMetricsFromTraceEvents() {
        MonitorService service = new MonitorService();

        ChatResponse response = new ChatResponse(
                "conv-1",
                "USER-001",
                "ok",
                List.of(
                        new TraceEvent("tool_observed", 1, "order tool success", Map.of(
                                "capability", "order_lookup",
                                "success", true,
                                "durationMs", 20
                        )),
                        new TraceEvent("tool_observed", 2, "return tool failed", Map.of(
                                "capability", "return_apply",
                                "success", false,
                                "durationMs", 10
                        )),
                        new TraceEvent("llm_called", 3, "call llm", Map.of()),
                        new TraceEvent("llm_failed", 3, "llm failed", Map.of(
                                "durationMs", 100
                        )),
                        new TraceEvent("llm_observed", 3, "llm fallback result", Map.of(
                                "durationMs", 120
                        )),
                        new TraceEvent("handoff_requested", 4, "request handoff", Map.of()),
                        new TraceEvent("handoff_created", 4, "create handoff ticket", Map.of(
                                "success", true,
                                "durationMs", 5
                        ))
                )
        );

        service.recordTurn(response);

        MonitorSummary summary = service.getSummary();
        assertEquals(1, summary.getTotalTurns());
        assertEquals(0, summary.getSuccessfulTurnCount());
        assertEquals(1, summary.getTechnicalFailureTurnCount());
        assertEquals(0.0, summary.getTurnSuccessRate());
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
        assertEquals(TurnOutcome.TECHNICAL_FAILURE, summary.getRecentTurns().get(0).getOutcome());
        assertEquals(3, summary.getToolStats().size());
        assertEquals(0, summary.getPendingEvalCandidateCount());
        assertEquals(0, summary.getApprovedEvalCandidateCount());
        assertEquals(0, summary.getRejectedEvalCandidateCount());
        assertEquals(0, summary.getEvalCandidateIssueCounts().get("MISSING_KEY_INFO"));
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
        assertEquals(12, summary.getSuccessfulTurnCount());
        assertEquals(1.0, summary.getTurnSuccessRate());
        assertEquals(10, summary.getRecentTurns().size());
        assertEquals("conv-11", summary.getRecentTurns().get(0).getConversationId());
        assertEquals("conv-2", summary.getRecentTurns().get(9).getConversationId());
    }

    @Test
    void shouldClassifyEachTurnIntoOneOutcomeBucket() {
        MonitorService service = new MonitorService();

        service.recordTurn(new ChatResponse("conv-success", "USER-001", "ok", List.of()));
        service.recordTurn(new ChatResponse(
                "conv-business",
                "USER-001",
                "not found",
                List.of(new TraceEvent("tool_observed", 1, "business rejected", Map.of(
                        "capability", "order.lookup",
                        "success", false,
                        "outcome", "BUSINESS_REJECTED",
                        "errorCode", "ORDER_NOT_FOUND"
                )))
        ));
        service.recordTurn(new ChatResponse(
                "conv-technical",
                "USER-001",
                "fallback",
                List.of(new TraceEvent("llm_failed", 1, "llm failed", Map.of()))
        ));
        service.recordTurn(new ChatResponse(
                "conv-guardrail",
                "USER-001",
                "denied",
                List.of(
                        new TraceEvent("capability_denied", 1, "allowlist denied", Map.of(
                                "capability", "return.apply"
                        )),
                        // 同轮存在技术事件时，显式安全拒绝仍必须成为最终分类。
                        new TraceEvent("tool_observed", 1, "compatibility result", Map.of(
                                "success", false,
                                "outcome", "TECHNICAL_FAILURE",
                                "errorCode", "CAPABILITY_NOT_ALLOWED"
                        ))
                )
        ));
        service.recordTurn(new ChatResponse(
                "conv-cancelled",
                "USER-001",
                "cancelled",
                List.of(new TraceEvent("workflow_completed", 1, "workflow terminal", Map.of(
                        "status", "CANCELLED"
                )))
        ));

        MonitorSummary summary = service.getSummary();
        assertEquals(5, summary.getTotalTurns());
        assertEquals(1, summary.getSuccessfulTurnCount());
        assertEquals(1, summary.getBusinessRejectedTurnCount());
        assertEquals(1, summary.getTechnicalFailureTurnCount());
        assertEquals(1, summary.getGuardrailRejectedTurnCount());
        assertEquals(1, summary.getUserCancelledTurnCount());
        assertEquals(0.2, summary.getTurnSuccessRate());
        assertEquals(TurnOutcome.USER_CANCELLED, summary.getRecentTurns().get(0).getOutcome());
        assertEquals(TurnOutcome.GUARDRAIL_REJECTED, summary.getRecentTurns().get(1).getOutcome());
        assertEquals("CAPABILITY_NOT_ALLOWED", summary.getRecentTurns().get(1).getErrorCode());
        assertEquals(TurnOutcome.BUSINESS_REJECTED, summary.getRecentTurns().get(3).getOutcome());
    }

    @Test
    void shouldRecordTechnicalFailureBeforeChatResponseExists() {
        MonitorService service = new MonitorService();

        service.recordFailedTurn(
                "conv-exception",
                "USER-001",
                TurnOutcome.TECHNICAL_FAILURE,
                "SESSION_VERSION_CONFLICT",
                37
        );

        MonitorSummary summary = service.getSummary();
        MonitorTurnSnapshot turn = summary.getRecentTurns().get(0);
        assertEquals(1, summary.getTotalTurns());
        assertEquals(1, summary.getTechnicalFailureTurnCount());
        assertEquals(TurnOutcome.TECHNICAL_FAILURE, turn.getOutcome());
        assertEquals("SESSION_VERSION_CONFLICT", turn.getErrorCode());
        assertEquals(37, turn.getTotalDurationMs());
        assertEquals(0, turn.getToolCalls());
    }

    @Test
    void shouldAggregateUserFeedback() {
        MonitorService service = new MonitorService();

        FeedbackRecord helpful = service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.HELPFUL,
                "answer helped"
        );
        FeedbackRecord unhelpful = service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "\u6ca1\u6709\u8bf4\u660e\u9000\u8d27\u65f6\u6548"
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
        assertEquals(1, summary.getEvalCandidateIssueCounts().get("MISSING_KEY_INFO"));
    }

    @Test
    void shouldCreateEvalCandidateFromUnhelpfulFeedback() {
        MonitorService service = new MonitorService();

        service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.HELPFUL,
                "answer clear",
                "order shipped"
        );
        service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "\u6ca1\u6709\u8bf4\u660e\u9000\u8d27\u65f6\u6548",
                "we can submit a return request"
        );

        MonitorSummary summary = service.getSummary();
        FeedbackEvalCandidate candidate = service.getFeedbackEvalCandidates().get(0);

        assertEquals(1, summary.getEvalCandidateCount());
        assertEquals(1, summary.getRecentEvalCandidates().size());
        assertEquals(1, summary.getPendingEvalCandidateCount());
        assertEquals("EVAL-CAND-000001", candidate.getCandidateId());
        assertEquals("FDB-000002", candidate.getFeedbackId());
        assertEquals("response_quality_candidate", candidate.getSuggestedEvalType());
        assertEquals(FeedbackIssueType.MISSING_KEY_INFO, candidate.getIssueType());
        assertEquals(FeedbackEvalCandidateStatus.PENDING_REVIEW, candidate.getStatus());
        assertEquals("\u6ca1\u6709\u8bf4\u660e\u9000\u8d27\u65f6\u6548", candidate.getReason());
        assertEquals("we can submit a return request", candidate.getAssistantReply());
    }

    @Test
    void shouldReviewEvalCandidate() {
        MonitorService service = new MonitorService();
        service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "\u56de\u7b54\u7f3a\u5c11\u5173\u952e\u4fe1\u606f",
                "we can handle it"
        );

        FeedbackEvalCandidate reviewed = service.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000001",
                FeedbackEvalCandidateStatus.APPROVED,
                "good negative sample"
        );

        assertEquals(FeedbackEvalCandidateStatus.APPROVED, reviewed.getStatus());
        assertEquals("good negative sample", reviewed.getReviewNote());
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
                "\u56de\u7b54\u7f3a\u5c11\u9000\u8d27\u65f6\u6548",
                "we can submit a return request"
        );
        service.recordFeedback(
                "conv-2",
                "USER-002",
                FeedbackRating.UNHELPFUL,
                "user clicked by mistake",
                "order shipped"
        );

        service.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000001",
                FeedbackEvalCandidateStatus.APPROVED,
                "can be used as a negative example"
        );
        service.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000002",
                FeedbackEvalCandidateStatus.REJECTED,
                "not exported"
        );

        List<FeedbackEvalCaseDraft> drafts = service.exportApprovedEvalCaseDrafts();

        assertEquals(1, drafts.size());
        assertEquals("feedback_eval_cand_000001", drafts.get(0).getId());
        assertEquals("EVAL-CAND-000001", drafts.get(0).getSourceCandidateId());
        assertEquals("we can submit a return request", drafts.get(0).getCandidateReply());
        assertEquals(FeedbackIssueType.MISSING_KEY_INFO, drafts.get(0).getIssueType());
        assertEquals(false, drafts.get(0).isExpectedPass());
        assertEquals("can be used as a negative example", drafts.get(0).getReviewNote());
        assertEquals(0, service.getSummary().getPendingEvalCandidateCount());
        assertEquals(1, service.getSummary().getApprovedEvalCandidateCount());
        assertEquals(1, service.getSummary().getRejectedEvalCandidateCount());
    }

    @Test
    void shouldClassifyEvalCandidateIssueTypes() {
        MonitorService service = new MonitorService();

        service.recordFeedback(
                "conv-1",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "\u56de\u590d\u627f\u8bfa\u4e00\u5b9a\u53ef\u4ee5\u9000\u6b3e",
                "\u4e00\u5b9a\u53ef\u4ee5\u9000\u6b3e"
        );
        service.recordFeedback(
                "conv-2",
                "USER-002",
                FeedbackRating.UNHELPFUL,
                "\u56de\u590d\u91cc\u51fa\u73b0\u5185\u90e8\u5de5\u5177\u8c03\u7528",
                "Tool order_lookup returned order data"
        );
        service.recordFeedback(
                "conv-3",
                "USER-003",
                FeedbackRating.UNHELPFUL,
                "\u8bed\u6c14\u592a\u6577\u884d\uff0c\u4f53\u9a8c\u4e0d\u597d",
                "read the policy yourself"
        );

        MonitorSummary summary = service.getSummary();

        assertEquals(FeedbackIssueType.TONE_OR_EXPERIENCE, service.getFeedbackEvalCandidates().get(0).getIssueType());
        assertEquals(FeedbackIssueType.TOOL_OR_INTERNAL_LEAK, service.getFeedbackEvalCandidates().get(1).getIssueType());
        assertEquals(FeedbackIssueType.UNSAFE_PROMISE, service.getFeedbackEvalCandidates().get(2).getIssueType());
        assertEquals(1, summary.getEvalCandidateIssueCounts().get("UNSAFE_PROMISE"));
        assertEquals(1, summary.getEvalCandidateIssueCounts().get("TOOL_OR_INTERNAL_LEAK"));
        assertEquals(1, summary.getEvalCandidateIssueCounts().get("TONE_OR_EXPERIENCE"));
    }

    @Test
    void shouldBuildMonitorAlertsFromRiskSignals() {
        MonitorService service = new MonitorService();

        service.recordTurn(new ChatResponse(
                "conv-risk",
                "USER-001",
                "fallback",
                List.of(
                        new TraceEvent("tool_observed", 1, "order tool failed", Map.of(
                                "capability", "order_lookup",
                                "success", false,
                                "durationMs", 20
                        )),
                        new TraceEvent("tool_observed", 2, "return tool failed", Map.of(
                                "capability", "return_apply",
                                "success", false,
                                "durationMs", 30
                        )),
                        new TraceEvent("tool_observed", 3, "product tool failed", Map.of(
                                "capability", "product_search",
                                "success", false,
                                "durationMs", 10
                        )),
                        new TraceEvent("llm_called", 4, "first llm call", Map.of()),
                        new TraceEvent("llm_failed", 4, "first llm failed", Map.of()),
                        new TraceEvent("llm_called", 5, "second llm call", Map.of()),
                        new TraceEvent("llm_failed", 5, "second llm failed", Map.of())
                )
        ));

        service.recordFeedback(
                "conv-risk",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "\u56de\u590d\u627f\u8bfa\u4e00\u5b9a\u53ef\u4ee5\u9000\u6b3e",
                "\u4e00\u5b9a\u53ef\u4ee5\u9000\u6b3e"
        );
        service.recordFeedback(
                "conv-risk",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "\u56de\u590d\u91cc\u51fa\u73b0\u4e86\u5185\u90e8\u5de5\u5177\u8c03\u7528",
                "Tool order_lookup returned order data"
        );
        service.recordFeedback(
                "conv-ok",
                "USER-002",
                FeedbackRating.HELPFUL,
                "answer helped"
        );

        MonitorSummary summary = service.getSummary();
        List<String> alertCodes = summary.getAlerts()
                .stream()
                .map(MonitorAlert::getCode)
                .toList();

        assertTrue(alertCodes.contains("TOOL_SUCCESS_RATE_LOW"));
        assertTrue(alertCodes.contains("LLM_FAILURE_RATE_HIGH"));
        assertTrue(alertCodes.contains("HELPFUL_FEEDBACK_RATE_LOW"));
        assertTrue(alertCodes.contains("UNSAFE_PROMISE_DETECTED"));
        assertTrue(alertCodes.contains("INTERNAL_DETAIL_LEAK_DETECTED"));
        assertEquals(MonitorAlertSeverity.CRITICAL, findAlert(summary, "LLM_FAILURE_RATE_HIGH").getSeverity());
        assertEquals(MonitorAlertSeverity.CRITICAL, findAlert(summary, "UNSAFE_PROMISE_DETECTED").getSeverity());
        assertEquals(MonitorAlertSeverity.CRITICAL, findAlert(summary, "INTERNAL_DETAIL_LEAK_DETECTED").getSeverity());
    }

    @Test
    void shouldPersistAndRestoreFeedbackEvalCandidatesAfterRestart() {
        Path storageFile = tempDir.resolve("feedback-eval-candidates.json");
        FeedbackEvalCandidateStore firstStore = new FeedbackEvalCandidateStore(storageFile, new ObjectMapper());
        MonitorService firstService = new MonitorService(firstStore);

        firstService.recordFeedback(
                "conv-persist",
                "USER-001",
                FeedbackRating.UNHELPFUL,
                "missing return timeline",
                "we can submit a return request"
        );
        firstService.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000001",
                FeedbackEvalCandidateStatus.APPROVED,
                "review note should survive restart"
        );

        FeedbackEvalCandidateStore secondStore = new FeedbackEvalCandidateStore(storageFile, new ObjectMapper());
        MonitorService secondService = new MonitorService(secondStore);

        MonitorSummary restoredSummary = secondService.getSummary();
        FeedbackEvalCandidate restoredCandidate = secondService.getFeedbackEvalCandidates().get(0);

        assertEquals(1, restoredSummary.getEvalCandidateCount());
        assertEquals(0, restoredSummary.getPendingEvalCandidateCount());
        assertEquals(1, restoredSummary.getApprovedEvalCandidateCount());
        assertEquals("EVAL-CAND-000001", restoredCandidate.getCandidateId());
        assertEquals(FeedbackEvalCandidateStatus.APPROVED, restoredCandidate.getStatus());
        assertEquals("review note should survive restart", restoredCandidate.getReviewNote());

        secondService.recordFeedback(
                "conv-persist-2",
                "USER-002",
                FeedbackRating.UNHELPFUL,
                "internal tool leaked",
                "Tool order_lookup returned data"
        );

        FeedbackEvalCandidate newestCandidate = secondService.getFeedbackEvalCandidates().get(0);
        assertEquals("EVAL-CAND-000002", newestCandidate.getCandidateId());
        assertEquals("FDB-000002", newestCandidate.getFeedbackId());
        assertEquals(2, secondService.getSummary().getEvalCandidateCount());
    }

    private ToolMonitorSnapshot findTool(MonitorSummary summary, String toolName) {
        return summary.getToolStats()
                .stream()
                .filter(tool -> tool.getToolName().equals(toolName))
                .findFirst()
                .orElseThrow();
    }

    private MonitorAlert findAlert(MonitorSummary summary, String code) {
        return summary.getAlerts()
                .stream()
                .filter(alert -> alert.getCode().equals(code))
                .findFirst()
                .orElseThrow();
    }
}
