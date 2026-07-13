package com.smartcs.smart_cs_agent.handoff.review;

/**
 * 管理员对一条待人工复核 Outbox 记录作出的业务决定。
 *
 * <p>这里使用枚举而不是自由文本，是为了让状态机、数据库和后续 HTTP API 共享同一组有限动作，
 * 避免出现 {@code retry}/{@code RETRY_NOW} 等含义相同但无法稳定统计的字符串。</p>
 */
public enum PendingHandoffManualAction {

    /** 重新交给 Recovery Worker 排队处理，不在管理员请求线程里直接调用远端。 */
    RETRY,

    /** 根据可核验的外部证据，确认售后工单已经存在。 */
    CONFIRM,

    /** 业务上确认无需再创建或关联工单，进入不可自动恢复的关闭终态。 */
    CLOSE
}
