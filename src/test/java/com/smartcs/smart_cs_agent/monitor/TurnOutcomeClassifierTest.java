package com.smartcs.smart_cs_agent.monitor;

import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** TurnOutcomeClassifier 只根据结构化 Trace 字段分类，不读取回复文案。 */
class TurnOutcomeClassifierTest {

    @Test
    void shouldRecognizeGuardrailFromGenericSecurityErrorCode() {
        TurnOutcomeClassifier.Assessment assessment = TurnOutcomeClassifier.classify(List.of(
                new TraceEvent("tool_observed", 1, "provider rejected", Map.of(
                        "success", false,
                        "outcome", "BUSINESS_REJECTED",
                        "errorCode", "ORDER_OWNERSHIP_MISMATCH"
                ))
        ));

        assertEquals(TurnOutcome.GUARDRAIL_REJECTED, assessment.outcome());
        assertEquals("ORDER_OWNERSHIP_MISMATCH", assessment.errorCode());
    }

    @Test
    void shouldTreatEmptyTraceAsSuccessfulTurn() {
        TurnOutcomeClassifier.Assessment assessment = TurnOutcomeClassifier.classify(List.of());

        assertEquals(TurnOutcome.SUCCESS, assessment.outcome());
        assertEquals("", assessment.errorCode());
    }

    @Test
    void shouldUseHighestPriorityOutcomeAcrossTheWholeTrace() {
        TurnOutcomeClassifier.Assessment assessment = TurnOutcomeClassifier.classify(List.of(
                new TraceEvent("tool_observed", 1, "business rejected", Map.of(
                        "outcome", "BUSINESS_REJECTED",
                        "errorCode", "ORDER_NOT_FOUND"
                )),
                new TraceEvent("workflow_completed", 2, "user cancelled", Map.of(
                        "status", "CANCELLED"
                )),
                new TraceEvent("llm_failed", 3, "llm failed", Map.of(
                        "errorCode", "MODEL_UNAVAILABLE"
                )),
                new TraceEvent("guardrail_rejected", 4, "access denied", Map.of(
                        "errorCode", "ACCESS_DENIED"
                ))
        ));

        // 同一轮可能同时出现多个事件。安全边界拒绝必须覆盖降级失败和业务结果，避免运维误判。
        assertEquals(TurnOutcome.GUARDRAIL_REJECTED, assessment.outcome());
        assertEquals("ACCESS_DENIED", assessment.errorCode());
    }

    @Test
    void shouldRecognizeWallClockTurnDeadlineAsTechnicalFailure() {
        TurnOutcomeClassifier.Assessment assessment = TurnOutcomeClassifier.classify(List.of(
                new TraceEvent("turn_deadline_exceeded", 2, "deadline", Map.of(
                        "budgetMs", 30_000
                ))
        ));

        assertEquals(TurnOutcome.TECHNICAL_FAILURE, assessment.outcome());
        assertEquals("TURN_DEADLINE_EXCEEDED", assessment.errorCode());
    }

    @Test
    void shouldPreferTurnDeadlineOverEarlierLlmComponentFailure() {
        TurnOutcomeClassifier.Assessment assessment = TurnOutcomeClassifier.classify(List.of(
                new TraceEvent("llm_failed", 2, "llm budget", Map.of(
                        "errorCode", "LLM_TURN_BUDGET_EXCEEDED"
                )),
                new TraceEvent("turn_deadline_exceeded", 2, "turn budget", Map.of(
                        "budgetMs", 30_000
                ))
        ));

        assertEquals(TurnOutcome.TECHNICAL_FAILURE, assessment.outcome());
        assertEquals("TURN_DEADLINE_EXCEEDED", assessment.errorCode());
    }
}
