package com.smartcs.smart_cs_agent.monitor;

import com.smartcs.smart_cs_agent.agent.context.TraceEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把一轮 Trace 中的结构化事实归并成唯一 TurnOutcome。
 *
 * <p>分类规则集中在 Monitor 层，Runtime 和 Workflow 不需要知道监控报表怎样统计。规则只依赖稳定事件名、
 * outcome、errorCode 和 Workflow status，不根据客服回复文案猜测结果。</p>
 */
final class TurnOutcomeClassifier {

    private TurnOutcomeClassifier() {
    }

    static Assessment classify(List<TraceEvent> trace) {
        Assessment selected = Assessment.success();
        if (trace == null) {
            return selected;
        }

        for (TraceEvent event : trace) {
            Assessment candidate = classifyEvent(event);
            if (shouldReplace(selected, candidate)) {
                selected = candidate;
            }
        }
        return selected;
    }

    /**
     * 先按结果大类优先级选择；同为技术失败时，整轮预算事件要覆盖前面的组件级失败。
     *
     * <p>真实顺序通常是 llm_failed 在前、turn_deadline_exceeded 在后。如果只比较 TurnOutcome，
     * 两者都是 TECHNICAL_FAILURE，Monitor 就会错误保留 LLM 组件码，看不到真正的整轮终止原因。</p>
     */
    private static boolean shouldReplace(Assessment selected, Assessment candidate) {
        int selectedPriority = priority(selected.outcome());
        int candidatePriority = priority(candidate.outcome());
        if (candidatePriority != selectedPriority) {
            return candidatePriority > selectedPriority;
        }
        return isTurnBudgetCode(candidate.errorCode()) && !isTurnBudgetCode(selected.errorCode());
    }

    private static boolean isTurnBudgetCode(String errorCode) {
        return "TURN_DEADLINE_EXCEEDED".equals(errorCode)
                || "TURN_STEP_BUDGET_EXCEEDED".equals(errorCode);
    }

    private static Assessment classifyEvent(TraceEvent event) {
        if (event == null || event.getEventType() == null) {
            return Assessment.success();
        }

        Map<String, Object> data = event.getData();
        String eventType = event.getEventType();
        String errorCode = text(data.get("errorCode"));

        return switch (eventType) {
            case "capability_denied", "guardrail_rejected" ->
                    new Assessment(TurnOutcome.GUARDRAIL_REJECTED, defaultCode(errorCode, "CAPABILITY_NOT_ALLOWED"));
            case "tool_observed", "handoff_failed" -> classifyCapabilityResult(data, errorCode, eventType);
            case "tool_failed" ->
                    new Assessment(TurnOutcome.TECHNICAL_FAILURE, defaultCode(errorCode, "TOOL_CALL_FAILED"));
            case "llm_failed" ->
                    new Assessment(TurnOutcome.TECHNICAL_FAILURE, defaultCode(errorCode, "LLM_CALL_FAILED"));
            case "action_handler_failed" ->
                    new Assessment(TurnOutcome.TECHNICAL_FAILURE, defaultCode(errorCode, "ACTION_HANDLER_EXECUTION_FAILED"));
            case "turn_timeout" ->
                    new Assessment(TurnOutcome.TECHNICAL_FAILURE, "TURN_STEP_BUDGET_EXCEEDED");
            case "turn_deadline_exceeded" ->
                    new Assessment(TurnOutcome.TECHNICAL_FAILURE, "TURN_DEADLINE_EXCEEDED");
            case "turn_failed" ->
                    new Assessment(TurnOutcome.TECHNICAL_FAILURE, defaultCode(errorCode, "CHAT_TURN_EXECUTION_FAILED"));
            case "workflow_completed" -> classifyWorkflowStatus(text(data.get("status")));
            case "workflow_state_transition" -> classifyWorkflowStatus(text(data.get("statusAfter")));
            default -> Assessment.success();
        };
    }

    private static Assessment classifyCapabilityResult(
            Map<String, Object> data,
            String errorCode,
            String eventType) {
        String outcome = text(data.get("outcome")).toUpperCase(Locale.ROOT);
        if ("BUSINESS_REJECTED".equals(outcome)) {
            if (isGuardrailErrorCode(errorCode)) {
                return new Assessment(
                        TurnOutcome.GUARDRAIL_REJECTED,
                        defaultCode(errorCode, "GUARDRAIL_REJECTED")
                );
            }
            return new Assessment(
                    TurnOutcome.BUSINESS_REJECTED,
                    defaultCode(errorCode, "BUSINESS_REJECTED")
            );
        }
        if ("GUARDRAIL_REJECTED".equals(outcome)) {
            return new Assessment(
                    TurnOutcome.GUARDRAIL_REJECTED,
                    defaultCode(errorCode, "GUARDRAIL_REJECTED")
            );
        }
        if ("TECHNICAL_FAILURE".equals(outcome)) {
            return new Assessment(
                    TurnOutcome.TECHNICAL_FAILURE,
                    defaultCode(errorCode, "CAPABILITY_TECHNICAL_FAILURE")
            );
        }

        // 兼容旧 Trace：旧事件只有 success=false，没有 outcome。
        if (Boolean.FALSE.equals(data.get("success")) || "handoff_failed".equals(eventType)) {
            return new Assessment(
                    TurnOutcome.TECHNICAL_FAILURE,
                    defaultCode(errorCode, "CAPABILITY_CALL_FAILED")
            );
        }
        return Assessment.success();
    }

    private static Assessment classifyWorkflowStatus(String status) {
        if ("CANCELLED".equalsIgnoreCase(status)) {
            return new Assessment(TurnOutcome.USER_CANCELLED, "USER_CANCELLED");
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            return new Assessment(TurnOutcome.TECHNICAL_FAILURE, "WORKFLOW_FAILED");
        }
        return Assessment.success();
    }

    /**
     * 旧 CapabilityOutcome 把权限拒绝放在 BUSINESS_REJECTED 中，因此迁移期按稳定错误码语义识别。
     * 这里只匹配通用安全词，不写订单、退货等业务名称。
     */
    static boolean isGuardrailErrorCode(String errorCode) {
        String code = text(errorCode).toUpperCase(Locale.ROOT);
        return code.contains("AUTHENTICATION")
                || code.contains("AUTHORIZATION")
                || code.contains("IDENTITY")
                || code.contains("OWNERSHIP")
                || code.contains("PERMISSION")
                || code.contains("ACCESS_DENIED")
                || code.contains("NOT_ALLOWED")
                || code.contains("DELEGATION")
                || code.contains("IDEMPOTENCY_KEY_REQUIRED");
    }

    private static int priority(TurnOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> 0;
            case BUSINESS_REJECTED -> 1;
            case USER_CANCELLED -> 2;
            case TECHNICAL_FAILURE -> 3;
            // Capability allowlist 拒绝当前还会产生一个兼容期 TECHNICAL_FAILURE ToolResult，
            // Guardrail 优先级必须更高，避免同一轮被错误计入技术故障。
            case GUARDRAIL_REJECTED -> 4;
        };
    }

    private static String defaultCode(String value, String fallback) {
        return value.isBlank() ? fallback : value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    record Assessment(TurnOutcome outcome, String errorCode) {

        Assessment {
            outcome = outcome == null ? TurnOutcome.SUCCESS : outcome;
            errorCode = errorCode == null ? "" : errorCode;
        }

        static Assessment success() {
            return new Assessment(TurnOutcome.SUCCESS, "");
        }
    }
}
