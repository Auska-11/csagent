package com.smartcs.smart_cs_agent.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 单轮断言。在每一轮 HTTP 响应返回后立即评估。
 *
 * <p>设计原则（对应 76 号计划 3.6 节）：安全与副作用指标全部是确定性断言，
 * 不依赖 Judge。断言对象有三类：</p>
 * <ul>
 *   <li>回复文本：replyMustContain / replyMustNotContain / replyMatchesAny；</li>
 *   <li>Trace 结构化事实：allowedOutcomes（复用生产 Monitor 的 TurnOutcome 分类）、
 *       mustTriggerCapabilities / mustNotTriggerCapabilities（tool_called 事件）；</li>
 *   <li>降级存活：expectDegradedReply 要求 llm_failed 发生且最终回复非空（HTTP 200）。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScenarioTurnAssertions {

    /** 回复必须包含以下全部关键词（contains，大小写敏感）。 */
    public List<String> replyMustContain;

    /** 回复中禁止出现以下任意关键词，常用于敏感信息泄露断言（张三、手机号、他人物流单号）。 */
    public List<String> replyMustNotContain;

    /** 回复至少命中一条正则；用于同一语义多种说法的断言（如确认/同意/好的）。 */
    public List<String> replyMatchesAny;

    /**
     * 本轮允许的 TurnOutcome 集合（生产 Monitor 分类词汇）：
     * SUCCESS / BUSINESS_REJECTED / USER_CANCELLED / TECHNICAL_FAILURE / GUARDRAIL_REJECTED。
     * 越权类场景预期 GUARDRAIL_REJECTED，故障类场景预期 TECHNICAL_FAILURE。
     */
    public List<String> allowedOutcomes;

    /** 本轮 tool_called 事件必须覆盖以下 capability 全集。 */
    public List<String> mustTriggerCapabilities;

    /** 本轮禁止触发以下 capability，用于“话术诱导调工具”的确定性断言。 */
    public List<String> mustNotTriggerCapabilities;

    /** 期待本轮发生 llm_failed 且仍返回非空回复（模板降级存活的定义）。 */
    public Boolean expectDegradedReply;

    /** 是否期待 HTTP 200；默认 true。本轮是降级场景时仍应为 200。 */
    public Boolean expectHttpOk = Boolean.TRUE;

    /**
     * 期待本轮的稳定错误码（如基线端 LLM 故障时 LLM_PROVIDER_ERROR）。
     * 与 allowedOutcomes 正交：outcome 是分类，errorCode 是定位。
     */
    public String expectedErrorCode;

    public String getExpectedErrorCode() {
        return expectedErrorCode;
    }

    public List<String> getReplyMustContain() {
        return replyMustContain;
    }

    public List<String> getReplyMustNotContain() {
        return replyMustNotContain;
    }

    public List<String> getReplyMatchesAny() {
        return replyMatchesAny;
    }

    public List<String> getAllowedOutcomes() {
        return allowedOutcomes;
    }

    public List<String> getMustTriggerCapabilities() {
        return mustTriggerCapabilities;
    }

    public List<String> getMustNotTriggerCapabilities() {
        return mustNotTriggerCapabilities;
    }

    public boolean expectsDegradedReply() {
        return Boolean.TRUE.equals(expectDegradedReply);
    }

    public boolean expectsHttpOk() {
        return !Boolean.FALSE.equals(expectHttpOk);
    }

    /**
     * 合并公共断言与端专属断言，供双跑时构造“该端实际生效的断言”。
     *
     * <p>列表类字段取并集（保持公共在前）；布尔类字段端专属块显式设置时优先，
     * 否则沿用公共块；expectedErrorCode 端专属块优先。任一块为 null 时直接返回另一块。</p>
     */
    static ScenarioTurnAssertions merge(ScenarioTurnAssertions common, ScenarioTurnAssertions sideSpecific) {
        if (common == null) {
            return sideSpecific;
        }
        if (sideSpecific == null) {
            return common;
        }
        ScenarioTurnAssertions merged = new ScenarioTurnAssertions();
        merged.replyMustContain = concat(common.replyMustContain, sideSpecific.replyMustContain);
        merged.replyMustNotContain = concat(common.replyMustNotContain, sideSpecific.replyMustNotContain);
        merged.replyMatchesAny = concat(common.replyMatchesAny, sideSpecific.replyMatchesAny);
        merged.allowedOutcomes = concat(common.allowedOutcomes, sideSpecific.allowedOutcomes);
        merged.mustTriggerCapabilities = concat(common.mustTriggerCapabilities, sideSpecific.mustTriggerCapabilities);
        merged.mustNotTriggerCapabilities = concat(common.mustNotTriggerCapabilities, sideSpecific.mustNotTriggerCapabilities);
        merged.expectDegradedReply = Boolean.TRUE.equals(sideSpecific.expectDegradedReply)
                || Boolean.TRUE.equals(common.expectDegradedReply);
        // 端专属块显式给出 expectHttpOk 时覆盖公共块（故障场景：公共块只写文案禁令，
        // 基线专属块写 expectHttpOk=false，此时公共块缺省的 true 必须让位）。
        merged.expectHttpOk = sideSpecific.expectHttpOk != null
                ? sideSpecific.expectHttpOk
                : common.expectHttpOk;
        merged.expectedErrorCode = sideSpecific.expectedErrorCode != null
                ? sideSpecific.expectedErrorCode
                : common.expectedErrorCode;
        return merged;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        if (first == null || first.isEmpty()) {
            return second;
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        List<String> combined = new java.util.ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }
}
