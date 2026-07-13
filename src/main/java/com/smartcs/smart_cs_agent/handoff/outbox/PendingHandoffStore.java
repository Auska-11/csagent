package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;

import java.util.Optional;

/**
 * 人工转接结果未知记录的持久化端口。
 *
 * <p>接口放在 Outbox 边界，上层不感知记录最终保存在 Map 还是 MySQL。相同 idempotencyKey 和相同
 * 请求必须返回同一条记录；相同 key 对应不同请求则必须拒绝，不能悄悄覆盖待核对证据。</p>
 */
public interface PendingHandoffStore {

    /** 在任何远端副作用发生前保存请求，并告诉调用方自己是否是首次执行者。 */
    PendingHandoffPreparation prepare(PendingHandoffCommand command);

    /** 远端响应未知时推进状态。expectedVersion 防止旧执行者覆盖更新状态。 */
    PendingHandoffRecord markResultUnknown(String idempotencyKey,
                                           String failureType,
                                           long expectedVersion);

    /** 保存已确认成功的远端结果；之后相同请求必须直接回放，不能再次调用远端。 */
    PendingHandoffRecord confirm(String idempotencyKey,
                                 AfterSalesTicketCreateResult result,
                                 long expectedVersion);

    Optional<PendingHandoffRecord> findByIdempotencyKey(String idempotencyKey);
}
