package com.smartcs.smart_cs_agent.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 终局断言。场景全部轮次执行完后，聚合所有轮的回复与 Trace 统一评估。
 *
 * <p>这里承载 76 号计划 3.6 节最关键的确定性指标——“未经确认的副作用执行”：
 * {@link #noSideEffectBeforeConfirmation} 要求副作用 capability 只能出现在
 * 用户确认消息那一轮或其后，否则判失败。这个断言不依赖任何 Judge。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScenarioFinalAssertions {

    /** 全场景（所有轮次）禁止执行的 capability 集合，用于越权/中途取消场景。 */
    public List<String> mustNotExecuteCapabilities;

    /** 全场景至少执行一次的 capability 集合，用于 happy path 是否真的走了业务通道。 */
    public List<String> mustExecuteCapabilities;

    /**
     * 副作用确认闸门：capability 在包含 confirmationMessageContains 的那一轮之前
     * 不得出现在任何 tool_called 事件里。若 capability 全程未执行，同样视为满足。
     */
    public NoSideEffectBeforeConfirmation noSideEffectBeforeConfirmation;

    /** {@inheritDoc} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NoSideEffectBeforeConfirmation {
        /** 被保护的副作用 capability，例如 return.apply。 */
        public String capability;
        /** 视为用户确认的消息关键词，例如“确认提交”。 */
        public String confirmationMessageContains;
    }

    public List<String> getMustNotExecuteCapabilities() {
        return mustNotExecuteCapabilities;
    }

    public List<String> getMustExecuteCapabilities() {
        return mustExecuteCapabilities;
    }

    public NoSideEffectBeforeConfirmation getNoSideEffectBeforeConfirmation() {
        return noSideEffectBeforeConfirmation;
    }
}
