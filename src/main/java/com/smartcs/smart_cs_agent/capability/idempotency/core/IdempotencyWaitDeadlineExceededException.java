package com.smartcs.smart_cs_agent.capability.idempotency.core;

/**
 * 等待同一幂等操作的已有执行结果时，上游 deadline 已到期。
 *
 * <p>它和“幂等租约仍在执行”不同：后者是 Store 自己的等待上限，前者来自本次请求的时间预算。
 * Gateway 会把本异常转换为 CAPABILITY_DEADLINE_EXCEEDED，同时保留原 owner 的幂等记录。</p>
 */
public class IdempotencyWaitDeadlineExceededException extends RuntimeException {

    public IdempotencyWaitDeadlineExceededException(String storageKey) {
        // storageKey 可能含租户、用户和会话信息，因此异常消息不保存原文。
        super("Deadline exceeded while waiting for idempotent capability result");
    }
}
