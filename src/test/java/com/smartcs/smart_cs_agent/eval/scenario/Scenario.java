package com.smartcs.smart_cs_agent.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 一条对照实验场景脚本：同一份 JSON 同时驱动 Workflow 侧和基线侧（公平原则第 5 条，
 * 相同消息序列、相同身份、相同种子数据）。
 *
 * <p>示例见 src/test/resources/eval/scenario/full/（六类场景各一个文件）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Scenario {

    /** 稳定 ID，报告与断言失败信息都用它定位，例如 adv-cross-user-order。 */
    public String id;

    /** 场景类别，见 {@link ScenarioCategory}。 */
    public String category;

    /** 场景意图说明：这条场景要证明什么、预期难点在哪，供人工审查。 */
    public String description;

    /**
     * 盲评意图：给 Judge 看的身份中立版场景意图（只描述用户诉求与好的服务标准，
     * 不出现任何被测方实现词）。description 会记录 M3 校准发现（含端点身份词与
     * 行为差异），直接进 Judge prompt 会破坏盲性，所以真实盲评必须读本字段。
     */
    public String judgeIntent;

    /** 发送所有消息时使用的身份。 */
    public ScenarioPersona persona;

    /** 种子数据与前提说明（人工可读），例如“ORD-001 属于 USER-001（收货人张三）”。 */
    public String setup;

    /** 多轮消息序列，按顺序发送到同一个 conversationId。 */
    public List<ScenarioTurn> turns;

    /** 终局断言；可为空。 */
    public ScenarioFinalAssertions finalAssertions;

    /** 仅 Workflow 端追加的终局断言；与公共终局断言合并后评估。 */
    public ScenarioFinalAssertions workflowFinalAssertions;

    /** 仅基线端追加的终局断言；与公共终局断言合并后评估。 */
    public ScenarioFinalAssertions baselineFinalAssertions;

    /**
     * 重复执行次数；对抗与政策幻觉类关键场景填 3，按 76 号计划 3.4 节做多数表决。
     */
    public Integer repeat = 1;

    public String getId() {
        return id;
    }

    public ScenarioCategory category() {
        return category == null ? null : ScenarioCategory.valueOf(category.trim().toUpperCase());
    }

    public int repeatCount() {
        return repeat == null || repeat < 1 ? 1 : Math.min(repeat, 3);
    }

    public List<ScenarioTurn> getTurns() {
        return turns == null ? List.of() : turns;
    }

    /** 返回指定端生效的终局断言：公共块与端专属块的串联（null 安全）。 */
    public List<ScenarioFinalAssertions> finalAssertionsFor(EndpointSide side) {
        ScenarioFinalAssertions sideSpecific = side == EndpointSide.WORKFLOW
                ? workflowFinalAssertions
                : baselineFinalAssertions;
        if (finalAssertions == null && sideSpecific == null) {
            return List.of();
        }
        if (finalAssertions == null) {
            return List.of(sideSpecific);
        }
        if (sideSpecific == null) {
            return List.of(finalAssertions);
        }
        return List.of(finalAssertions, sideSpecific);
    }
}
