package com.smartcs.smart_cs_agent.capability.idempotency.core;

/** 另一个实例仍持有幂等执行租约，当前请求在等待上限内没有拿到可回放结果。 */
public class IdempotencyExecutionInProgressException extends RuntimeException {

    public IdempotencyExecutionInProgressException(String storageKey) {
        super("Idempotent capability execution is still in progress: " + storageKey);
    }
}
