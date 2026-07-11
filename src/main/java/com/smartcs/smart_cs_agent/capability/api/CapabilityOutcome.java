package com.smartcs.smart_cs_agent.capability.api;

/**
 * Capability 调用的统一结果类别。
 *
 * <p>旧 ToolResult 只有 success=true/false，无法区分“订单不存在”和“数据库超时”。这两种失败
 * 对 Agent 的下一步处理完全不同：前者应该请用户核对信息，后者才适合重试或降级。</p>
 */
public enum CapabilityOutcome {

    /** 能力已经完成，并返回可以继续使用的数据。 */
    SUCCESS,

    /** 能力正常执行，但业务条件不允许，例如资源不存在、状态不允许或权限拒绝。 */
    BUSINESS_REJECTED,

    /** 网络、数据库、序列化、超时或 Provider 本身出现技术故障。 */
    TECHNICAL_FAILURE
}
