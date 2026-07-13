package com.smartcs.smart_cs_agent.handoff.outbox;

/**
 * 人工工单 Outbox 的生命周期状态。
 *
 * <p>当前批次先使用 {@link #RESULT_UNKNOWN}，后续补建执行器会把记录推进到其他终态。
 * 提前定义完整状态，是为了避免以后使用多个互相冲突的 boolean 字段表达流程。</p>
 */
public enum PendingHandoffStatus {

    /** 已在调用远端前保存请求，但还没有拿到可确认的执行结果。 */
    PREPARED,

    /** Agent 没有拿到远端响应，远端究竟成功还是失败仍需核对。 */
    RESULT_UNKNOWN,

    /** 后台执行器正在核对或使用同一个幂等键补建。 */
    PROCESSING,

    /** 已确认远端存在对应工单。 */
    CONFIRMED,

    /** 临时失败，等待下一次有限重试。 */
    RETRY_SCHEDULED,

    /** 自动处理无法继续，需要人工核对。 */
    NEEDS_MANUAL_REVIEW,

    /** 人工确认无需再创建或关联远端工单；Worker 不得重新扫描这个终态。 */
    MANUALLY_CLOSED
}
