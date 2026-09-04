package com.smartcs.smart_cs_agent.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 场景中的一轮：用户消息 + 可选故障注入 + 本轮断言。
 *
 * <p>M3 起支持三段断言：{@link #assertions} 两端通用；
 * {@link #workflowAssertions} / {@link #baselineAssertions} 只在对应端评估，
 * 用于双端行为合法但不同构的场景（如故障注入：Workflow 期待模板降级 200，
 * 基线期待 503），以及“诚实记录基线行为”的对照证据场景（如越权退货时基线把
 * return.apply 调用发到了 Provider 边界）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScenarioTurn {

    /** 用户本轮发送的消息原文。 */
    public String message;

    /** 本轮注入的故障；缺省为不注入。 */
    public ScenarioFaultInjection faultInjection;

    /** 本轮断言；缺省只检查 HTTP 200。 */
    public ScenarioTurnAssertions assertions;

    /** 仅 Workflow 端追加的断言；与公共断言合并后评估。 */
    public ScenarioTurnAssertions workflowAssertions;

    /** 仅基线端追加的断言；与公共断言合并后评估。 */
    public ScenarioTurnAssertions baselineAssertions;

    public String getMessage() {
        return message;
    }

    public ScenarioFaultInjection getFaultInjection() {
        return faultInjection;
    }

    public ScenarioTurnAssertions getAssertions() {
        return assertions;
    }

    public ScenarioTurnAssertions getWorkflowAssertions() {
        return workflowAssertions;
    }

    public ScenarioTurnAssertions getBaselineAssertions() {
        return baselineAssertions;
    }

    /** 返回指定端生效的断言：公共块与端专属块的合并结果。 */
    public ScenarioTurnAssertions assertionsFor(EndpointSide side) {
        ScenarioTurnAssertions sideSpecific = side == EndpointSide.WORKFLOW
                ? workflowAssertions
                : baselineAssertions;
        return ScenarioTurnAssertions.merge(assertions, sideSpecific);
    }
}
