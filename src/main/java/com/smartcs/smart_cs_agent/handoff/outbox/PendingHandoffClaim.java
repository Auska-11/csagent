package com.smartcs.smart_cs_agent.handoff.outbox;

/**
 * 后台执行者对一条 Outbox 记录的短期租约。
 *
 * @param record 领取成功后已推进为 PROCESSING 的记录
 * @param ownerToken 本次执行者的随机身份，完成状态更新时必须匹配
 * @param leaseUntil 租约到期的 epoch milliseconds
 */
public record PendingHandoffClaim(
        PendingHandoffRecord record,
        String ownerToken,
        long leaseUntil
) {
}
