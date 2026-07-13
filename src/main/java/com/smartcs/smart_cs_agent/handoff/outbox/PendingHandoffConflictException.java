package com.smartcs.smart_cs_agent.handoff.outbox;

/** 相同外部幂等键被用于不同的工单请求。 */
public class PendingHandoffConflictException extends RuntimeException {

    public PendingHandoffConflictException() {
        // 不把幂等键拼进异常，避免日志无意记录会话标识。
        super("Pending handoff idempotency key conflicts with an existing request");
    }
}
