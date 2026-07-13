package com.smartcs.smart_cs_agent.handoff.reconciliation;

/**
 * 售后工单幂等查询的三态结论。
 *
 * <p>NOT_FOUND 表示下游成功完成了权威查询并明确没有记录；UNAVAILABLE 表示当前根本无法得到可靠答案。
 * 两者不能合并，否则网络故障或平台不支持查询时，系统可能错误地认为工单没有创建。</p>
 */
public enum AfterSalesTicketLookupOutcome {

    /** 已查询到与租户和幂等键匹配的工单。 */
    FOUND,

    /** 查询能力正常工作，并明确确认不存在匹配工单。 */
    NOT_FOUND,

    /** 查询协议不受支持、依赖临时故障或响应无法验证，当前没有权威结论。 */
    UNAVAILABLE
}
