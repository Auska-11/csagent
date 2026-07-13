package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;

import java.util.List;
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

    /** 查询已经到期、可以由后台处理的记录。 */
    List<PendingHandoffRecord> findDue(long now, int limit);

    /** 使用短租约原子领取；其他实例已经持有未过期租约时返回 empty。 */
    Optional<PendingHandoffClaim> tryClaim(String idempotencyKey,
                                           long expectedVersion,
                                           String ownerToken,
                                           long now,
                                           long leaseMs);

    /** 本次处理临时失败，安排下一次重试并释放租约。 */
    PendingHandoffRecord scheduleRetry(PendingHandoffClaim claim,
                                       String failureType,
                                       long nextAttemptAt);

    /** 达到最大尝试次数或遇到确定性问题，转入人工复核并释放租约。 */
    PendingHandoffRecord markNeedsManualReview(PendingHandoffClaim claim,
                                               String failureType);

    /** Worker 已成功确认或主动放弃本次处理时释放自己的租约。 */
    void releaseClaim(PendingHandoffClaim claim);

    Optional<PendingHandoffRecord> findByIdempotencyKey(String idempotencyKey);
}
