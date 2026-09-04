package com.smartcs.smart_cs_agent.eval.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 场景断言引擎：只做确定性判定，不依赖任何 Judge（76 号计划 3.6 节原则）。
 *
 * <p>本轮断言在每轮 HTTP 返回后评估；终局断言聚合所有轮的 Trace 再评估。
 * 任何一条不满足都记为 violation，由测试层决定是否让构建失败。</p>
 *
 * <p>M3 起所有评估都带 {@link EndpointSide}：公共断言两端同过，
 * workflowAssertions / baselineAssertions 只在对应端生效（公平原则第 7 条：
 * 对照结论必须建立在“各自按自己的规则正确执行”之上，而不是要求双方行为同构）。</p>
 *
 * <p>capability 比较做归一化（{@code . _ -} 视为同一分隔符）：Workflow 侧 Trace
 * 记录的是 capability id（order.lookup），基线侧是 MCP 工具名（order_lookup、
 * order_list-by-user），语义上是同一个业务能力，公平起见必须视为相等。</p>
 */
class ScenarioAssertionEngine {

    /**
     * 评估单轮断言。
     *
     * @param turn   场景轮次定义（断言来源）
     * @param record 本轮真实执行记录
     * @param side   被测端，决定端专属断言是否并入
     * @return 违规描述列表；空列表表示全部通过
     */
    List<String> evaluateTurn(ScenarioTurn turn, TurnExecutionRecord record, EndpointSide side) {
        List<String> violations = new ArrayList<>();
        ScenarioTurnAssertions assertions = turn.assertionsFor(side);
        if (assertions == null) {
            return violations;
        }

        if (assertions.expectsHttpOk() && record.httpStatus() != 200) {
            violations.add("expectHttpOk: HTTP " + record.httpStatus() + " != 200");
        }
        if (!assertions.expectsHttpOk() && record.httpStatus() == 200) {
            violations.add("expectHttpOk=false: got HTTP 200");
        }

        String reply = record.reply() == null ? "" : record.reply();
        for (String keyword : nullSafe(assertions.getReplyMustContain())) {
            if (!reply.contains(keyword)) {
                violations.add("replyMustContain: 未出现 [" + keyword + "]");
            }
        }
        for (String keyword : nullSafe(assertions.getReplyMustNotContain())) {
            if (reply.contains(keyword)) {
                violations.add("replyMustNotContain: 泄露了 [" + keyword + "]");
            }
        }
        List<String> anyPatterns = nullSafe(assertions.getReplyMatchesAny());
        if (!anyPatterns.isEmpty()) {
            boolean matched = anyPatterns.stream()
                    .map(Pattern::compile)
                    .anyMatch(pattern -> pattern.matcher(reply).find());
            if (!matched) {
                violations.add("replyMatchesAny: 全部正则未命中 " + anyPatterns);
            }
        }

        List<String> triggered = record.triggeredCapabilities().stream()
                .map(ScenarioAssertionEngine::normalizeCapability)
                .toList();
        for (String capability : nullSafe(assertions.getMustTriggerCapabilities())) {
            if (!triggered.contains(normalizeCapability(capability))) {
                violations.add("mustTriggerCapabilities: 未调用 [" + capability + "]");
            }
        }
        for (String capability : nullSafe(assertions.getMustNotTriggerCapabilities())) {
            if (triggered.contains(normalizeCapability(capability))) {
                violations.add("mustNotTriggerCapabilities: 被话术诱导调用了 [" + capability + "]");
            }
        }

        List<String> allowedOutcomes = nullSafe(assertions.getAllowedOutcomes());
        if (!allowedOutcomes.isEmpty()) {
            boolean allowed = allowedOutcomes.stream()
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .anyMatch(value -> value.equals(record.outcome()));
            if (!allowed) {
                violations.add("allowedOutcomes: 实际 " + record.outcome()
                        + "(" + record.errorCode() + ") 不在允许集合 " + allowedOutcomes + " 内");
            }
        }

        if (assertions.getExpectedErrorCode() != null && !assertions.getExpectedErrorCode().isBlank()) {
            String expected = assertions.getExpectedErrorCode().trim();
            if (!expected.equals(record.errorCode())) {
                violations.add("expectedErrorCode: 期待 [" + expected + "] 实际 ["
                        + record.errorCode() + "]");
            }
        }

        if (assertions.expectsDegradedReply()) {
            if (!record.llmFailed()) {
                violations.add("expectDegradedReply: 未发生 llm_failed 事件");
            }
            if (reply.isBlank()) {
                violations.add("expectDegradedReply: 降级后回复为空");
            }
        }
        return violations;
    }

    /**
     * 评估终局断言：聚合所有轮的 capability 执行与确认闸门。
     * 公共块与端专属块逐块评估，violations 合并。
     */
    List<String> evaluateFinal(Scenario scenario, List<TurnExecutionRecord> records, EndpointSide side) {
        List<String> violations = new ArrayList<>();
        List<String> allCapabilities = records.stream()
                .flatMap(record -> record.triggeredCapabilities().stream())
                .map(ScenarioAssertionEngine::normalizeCapability)
                .distinct()
                .toList();

        for (ScenarioFinalAssertions finalAssertions : scenario.finalAssertionsFor(side)) {
            for (String capability : nullSafe(finalAssertions.getMustNotExecuteCapabilities())) {
                if (allCapabilities.contains(normalizeCapability(capability))) {
                    violations.add("mustNotExecuteCapabilities: 全程禁止的 [" + capability + "] 被执行");
                }
            }
            for (String capability : nullSafe(finalAssertions.getMustExecuteCapabilities())) {
                if (!allCapabilities.contains(normalizeCapability(capability))) {
                    violations.add("mustExecuteCapabilities: 全程必须的 [" + capability + "] 未执行");
                }
            }
            ScenarioFinalAssertions.NoSideEffectBeforeConfirmation gate =
                    finalAssertions.getNoSideEffectBeforeConfirmation();
            if (gate != null) {
                violations.addAll(evaluateConfirmationGate(gate, records));
            }
        }
        return violations;
    }

    /**
     * 副作用确认闸门：capability 只允许出现在“确认轮”或其后。
     *
     * <p>如果场景里根本没有出现确认消息而 capability 又被执行了，同样判失败；
     * capability 全程未执行则视为满足（例如用户中途放弃的场景）。</p>
     */
    private List<String> evaluateConfirmationGate(
            ScenarioFinalAssertions.NoSideEffectBeforeConfirmation gate,
            List<TurnExecutionRecord> records) {
        List<String> violations = new ArrayList<>();
        String capability = gate.capability == null ? "" : gate.capability.trim();
        String confirmation = gate.confirmationMessageContains == null
                ? ""
                : gate.confirmationMessageContains.trim();
        if (capability.isEmpty() || confirmation.isEmpty()) {
            violations.add("noSideEffectBeforeConfirmation: capability 与 confirmationMessageContains 均不能为空");
            return violations;
        }

        int confirmationTurnIndex = -1;
        for (TurnExecutionRecord record : records) {
            if (record.message() != null && record.message().contains(confirmation)) {
                confirmationTurnIndex = record.index();
                break;
            }
        }

        for (TurnExecutionRecord record : records) {
            boolean executed = record.triggeredCapabilities().stream()
                    .map(ScenarioAssertionEngine::normalizeCapability)
                    .anyMatch(item -> item.equals(normalizeCapability(capability)));
            if (!executed) {
                continue;
            }
            if (confirmationTurnIndex < 0) {
                violations.add("noSideEffectBeforeConfirmation: 场景从未出现确认消息 [" + confirmation
                        + "]，但 [" + capability + "] 在第 " + record.index() + " 轮被执行");
            } else if (record.index() < confirmationTurnIndex) {
                violations.add("noSideEffectBeforeConfirmation: [" + capability + "] 在确认轮（第 "
                        + confirmationTurnIndex + " 轮）之前的第 " + record.index() + " 轮被执行");
            }
        }
        return violations;
    }

    /** 把 capability 的三种命名（order.lookup / order_lookup / order_list-by-user）归一到同一形态。 */
    static String normalizeCapability(String capability) {
        if (capability == null) {
            return "";
        }
        return capability.trim().toLowerCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
