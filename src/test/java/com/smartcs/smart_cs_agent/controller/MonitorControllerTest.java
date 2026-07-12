package com.smartcs.smart_cs_agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.monitor.FeedbackIssueType;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.MonitorSummary;
import com.smartcs.smart_cs_agent.monitor.ResponseQualityEvalCaseFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MonitorController unit tests.
 *
 * Tests use direct controller instances instead of a Spring container, so they can focus on
 * whether the controller correctly connects HTTP-layer DTOs with MonitorService.
 */
class MonitorControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldReturnMonitorSummary() {
        MonitorService service = new MonitorService();
        service.recordTurn(new ChatResponse(
                "conv-1",
                "USER-001",
                "ok",
                List.of(new TraceEvent("tool_observed", 1, "tool success", Map.of(
                        "success", true,
                        "durationMs", 15
                )))
        ));

        MonitorController controller = newController(service);

        MonitorSummary summary = controller.getSummary();
        assertEquals(1, summary.getTotalTurns());
        assertEquals(1, summary.getSuccessfulTurnCount());
        assertEquals(1.0, summary.getTurnSuccessRate());
        assertEquals(1, summary.getToolCallCount());
        assertEquals(1, summary.getSuccessfulToolCallCount());
    }

    @Test
    void shouldRecordFeedback() {
        MonitorService service = new MonitorService();
        MonitorController controller = newController(service);
        FeedbackRequest request = new FeedbackRequest(
                "conv-1",
                "USER-001",
                "HELPFUL",
                "clear answer",
                "order shipped"
        );

        FeedbackResponse response = controller.recordFeedback(request, customer());
        MonitorSummary summary = controller.getSummary();

        assertTrue(response.isSuccess());
        assertEquals("FDB-000001", response.getFeedback().getFeedbackId());
        assertEquals("HELPFUL", response.getFeedback().getRating().name());
        assertEquals("order shipped", response.getFeedback().getAssistantReply());
        assertEquals(1, summary.getFeedbackCount());
        assertEquals(1.0, summary.getHelpfulFeedbackRate());
    }

    @Test
    void shouldReturnFeedbackEvalCandidates() {
        MonitorService service = new MonitorService();
        MonitorController controller = newController(service);
        FeedbackRequest request = new FeedbackRequest(
                "conv-1",
                "USER-001",
                "UNHELPFUL",
                "\u6ca1\u6709\u8bf4\u660e\u9000\u8d27\u65f6\u6548",
                "we can submit a return request"
        );

        controller.recordFeedback(request, customer());

        assertEquals(1, controller.getFeedbackEvalCandidates().size());
        assertEquals("EVAL-CAND-000001", controller.getFeedbackEvalCandidates().get(0).getCandidateId());
        assertEquals("we can submit a return request", controller.getFeedbackEvalCandidates().get(0).getAssistantReply());
        assertEquals(FeedbackIssueType.MISSING_KEY_INFO, controller.getFeedbackEvalCandidates().get(0).getIssueType());
    }

    @Test
    void shouldReviewFeedbackEvalCandidate() {
        MonitorService service = new MonitorService();
        MonitorController controller = newController(service);
        controller.recordFeedback(new FeedbackRequest(
                "conv-1",
                "USER-001",
                "UNHELPFUL",
                "\u6ca1\u6709\u8bf4\u660e\u9000\u8d27\u65f6\u6548",
                "we can submit a return request"
        ), customer());

        EvalCandidateReviewResponse response = controller.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000001",
                new EvalCandidateReviewRequest("APPROVED", "can enter response quality eval")
        );

        assertTrue(response.isSuccess());
        assertEquals("APPROVED", response.getCandidate().getStatus().name());
        assertEquals("can enter response quality eval", response.getCandidate().getReviewNote());
        assertEquals(0, controller.getSummary().getPendingEvalCandidateCount());
        assertEquals(1, controller.getSummary().getApprovedEvalCandidateCount());
        assertEquals(0, controller.getSummary().getRejectedEvalCandidateCount());
    }

    @Test
    void shouldExportApprovedEvalCaseDrafts() {
        MonitorService service = new MonitorService();
        MonitorController controller = newController(service);
        controller.recordFeedback(new FeedbackRequest(
                "conv-1",
                "USER-001",
                "UNHELPFUL",
                "\u6ca1\u6709\u8bf4\u660e\u9000\u8d27\u65f6\u6548",
                "we can submit a return request"
        ), customer());
        controller.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000001",
                new EvalCandidateReviewRequest("APPROVED", "can be used as a negative sample")
        );

        assertEquals(1, controller.exportApprovedEvalCaseDrafts().size());
        assertEquals(
                "feedback_eval_cand_000001",
                controller.exportApprovedEvalCaseDrafts().get(0).getId()
        );
    }

    @Test
    void shouldMergeApprovedEvalCaseDraftsIntoResponseQualityFile() {
        MonitorService service = new MonitorService();
        MonitorController controller = newController(service);
        controller.recordFeedback(new FeedbackRequest(
                "conv-merge",
                "USER-001",
                "UNHELPFUL",
                "\u6ca1\u6709\u8bf4\u660e\u9000\u8d27\u65f6\u6548",
                "we can submit a return request"
        ), customer());
        controller.reviewFeedbackEvalCandidate(
                "EVAL-CAND-000001",
                new EvalCandidateReviewRequest("APPROVED", "requiredKeywords completed")
        );

        EvalCaseMergeResponse response = controller.mergeApprovedEvalCaseDrafts(new EvalCaseMergeRequest(List.of(
                new EvalCaseMergeItem(
                        "EVAL-CAND-000001",
                        "feedback_eval_cand_000001",
                        "we can submit a return request",
                        List.of("\u9000\u8d27\u65f6\u6548"),
                        List.of("Tool"),
                        false
                )
        )));

        assertTrue(response.isSuccess());
        assertEquals(1, response.getResult().getMergedCount());
        assertEquals(0, response.getResult().getBeforeCount());
        assertEquals(1, response.getResult().getAfterCount());

        EvalCaseMergeResponse duplicated = controller.mergeApprovedEvalCaseDrafts(new EvalCaseMergeRequest(List.of(
                new EvalCaseMergeItem(
                        "EVAL-CAND-000001",
                        "feedback_eval_cand_000001",
                        "we can submit a return request",
                        List.of("\u9000\u8d27\u65f6\u6548"),
                        List.of("Tool"),
                        false
                )
        )));

        assertEquals(0, duplicated.getResult().getMergedCount());
        assertEquals(List.of("feedback_eval_cand_000001"), duplicated.getResult().getSkippedDuplicateIds());
    }

    @Test
    void shouldRejectMergeWhenCandidateIsNotApproved() {
        MonitorController controller = newController(new MonitorService());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> controller.mergeApprovedEvalCaseDrafts(new EvalCaseMergeRequest(List.of(
                        new EvalCaseMergeItem(
                                "EVAL-CAND-404",
                                "case-404",
                                "reply",
                                List.of("keyword"),
                                List.of(),
                                false
                        )
                )))
        );

        assertEquals("Eval candidate is not approved or does not exist: EVAL-CAND-404", exception.getMessage());
    }

    @Test
    void shouldRejectInvalidEvalCandidateStatus() {
        MonitorController controller = newController(new MonitorService());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> controller.reviewFeedbackEvalCandidate(
                        "EVAL-CAND-000001",
                        new EvalCandidateReviewRequest("PENDING_REVIEW", "")
                )
        );

        assertEquals("Candidate status must be APPROVED or REJECTED", exception.getMessage());
    }

    @Test
    void shouldRejectInvalidFeedbackRating() {
        MonitorController controller = newController(new MonitorService());
        FeedbackRequest request = new FeedbackRequest("conv-1", "USER-001", "LIKE", "");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> controller.recordFeedback(request, customer())
        );

        assertEquals("Rating must be HELPFUL or UNHELPFUL", exception.getMessage());
    }

    private MonitorController newController(MonitorService service) {
        Path evalFile = tempDir.resolve("response_quality_eval_cases.json");
        return new MonitorController(
                service,
                new ResponseQualityEvalCaseFileService(new ObjectMapper(), evalFile.toString())
        );
    }

    private AuthenticatedPrincipal customer() {
        return AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER"));
    }
}
