package com.smartcs.smart_cs_agent.handoff.outbox;

/** Outbox 状态已被另一个执行者推进，当前调用持有的 version 已过期。 */
public class PendingHandoffVersionConflictException extends RuntimeException {

    public PendingHandoffVersionConflictException() {
        super("Pending handoff record version has changed");
    }
}
