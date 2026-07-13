package com.smartcs.smart_cs_agent.handoff.review;

/**
 * 支撑人工决定的证据来源。
 *
 * <p>证据来源和操作者身份是两件事：管理员可以录入自己在售后后台核对到的结果，也可以使用未来
 * 20.5 提供的按幂等键查询能力。把来源单独保存，面试或事故复盘时才能解释“为什么确认成功”。</p>
 */
public enum PendingHandoffEvidenceSource {

    /** retry/close 不声明远端成功，因此没有成功证据。 */
    NONE,

    /** 管理员从受信任的售后后台核对并录入证据。 */
    ADMIN_PROVIDED,

    /** 系统通过售后平台的 Idempotency-Key 查询接口核对到证据。 */
    AFTER_SALES_IDEMPOTENCY_QUERY
}
