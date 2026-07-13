package com.smartcs.smart_cs_agent.handoff.reconciliation;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffKey;

/**
 * 按租户与原始幂等键核对售后工单结果的出站 Port。
 *
 * <p>Port 必须是只读的，不能偷偷调用“创建工单”来模拟查询。未来可以接 HTTP、数据库只读副本或其他
 * 售后系统，但 Admin Service 只依赖这份三态契约，不依赖任何传输协议。</p>
 */
public interface AfterSalesTicketResultLookup {

    /**
     * 查询指定租户和幂等键对应的远端工单。
     *
     * @param key 同时包含可信 tenantId 与 Outbox 原始 idempotencyKey 的复合键
     * @return FOUND、权威 NOT_FOUND 或明确 UNAVAILABLE，绝不能用 null 表示未知
     * @implNote 可预期的超时、连接错误和协议错误必须转换成 UNAVAILABLE；只有编程错误才允许抛异常
     */
    AfterSalesTicketLookupResult findByIdempotencyKey(PendingHandoffKey key);
}
