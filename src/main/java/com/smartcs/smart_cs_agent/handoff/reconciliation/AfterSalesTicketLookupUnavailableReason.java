package com.smartcs.smart_cs_agent.handoff.reconciliation;

/** UNAVAILABLE 的结构化原因；不保存 URL、凭据、幂等键或底层异常原文。 */
public enum AfterSalesTicketLookupUnavailableReason {

    /** 当前售后平台没有公开按 Idempotency-Key 查询的只读接口。 */
    QUERY_NOT_SUPPORTED,

    /** 查询协议存在，但本次超时、连接失败或下游暂时不可用。 */
    REMOTE_UNAVAILABLE,

    /** 下游声称查询成功，但返回结果缺少可信工单字段。 */
    INVALID_RESPONSE
}
