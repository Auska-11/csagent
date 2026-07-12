package com.smartcs.smart_cs_agent.capability.idempotency.core;

/** 同一个幂等键被用于不同请求参数时抛出，防止错误回放另一次操作结果。 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String storageKey) {
        super("Idempotency key was reused with different arguments: " + storageKey);
    }
}
