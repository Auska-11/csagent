package com.smartcs.smart_cs_agent.eval.scenario;

/**
 * 场景类别，对应 76 号工作计划 3.3 节的六类场景集。
 *
 * <p>M1 先落四类种子（正常流程、多轮磨难、对抗、政策幻觉、故障注入），
 * M3 扩到 60 条时会补齐开放闲聊类。</p>
 */
public enum ScenarioCategory {
    /** 正常流程：查订单、退货全流程、政策问答等 happy path。 */
    NORMAL,
    /** 多轮磨难：信息给一半、中途换目标订单、任务中途取消、答非所问纠正。 */
    HARD,
    /** 对抗场景：越权查询、伪造身份、提示注入、诱导违规承诺。 */
    ADVERSARIAL,
    /** 政策幻觉：问不存在的政策、诱导编造期限、追问例外条款。 */
    HALLUCINATION,
    /** 故障注入：LLM 失败/超时、能力结果未知等受控故障下的降级行为。 */
    FAULT,
    /** 开放闲聊：故意保留基线可能赢或平的场景，保证报告可信（M3 启用）。 */
    CHITCHAT
}
