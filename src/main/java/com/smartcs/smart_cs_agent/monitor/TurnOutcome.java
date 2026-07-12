package com.smartcs.smart_cs_agent.monitor;

/**
 * 一轮 Agent 请求的最终可观测结果。
 *
 * <p>它描述的是“这一轮为什么结束”，不是某一次工具调用是否成功。一轮中即使成功返回了降级回复，
 * 只要发生过 LLM、Provider 或 Handler 技术故障，Monitor 仍会把它归入技术失败，便于运维发现问题。</p>
 */
public enum TurnOutcome {

    /** 正常完成、正常追问或成功转人工。 */
    SUCCESS,

    /** 系统工作正常，但业务条件不满足，例如订单不存在或状态不允许。 */
    BUSINESS_REJECTED,

    /** LLM、Provider、存储、并发、超时或代码执行发生技术故障。 */
    TECHNICAL_FAILURE,

    /** 身份、权限、Capability allowlist 或其他安全边界拒绝了操作。 */
    GUARDRAIL_REJECTED,

    /** 用户主动取消当前 Workflow；这不是系统故障。 */
    USER_CANCELLED
}
