package com.smartcs.smart_cs_agent.handoff.outbox;

import java.util.Optional;

/**
 * 人工转接结果未知记录的持久化端口。
 *
 * <p>接口放在 Outbox 边界，上层不感知记录最终保存在 Map 还是 MySQL。相同 idempotencyKey 和相同
 * 请求必须返回同一条记录；相同 key 对应不同请求则必须拒绝，不能悄悄覆盖待核对证据。</p>
 */
public interface PendingHandoffStore {

    PendingHandoffRecord recordUnknown(PendingHandoffCommand command);

    Optional<PendingHandoffRecord> findByIdempotencyKey(String idempotencyKey);
}
