package com.smartcs.smart_cs_agent.handoff.review;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;

/** 人工操作与记录当前状态不兼容，例如尝试关闭已经确认成功的工单。 */
public class PendingHandoffStateConflictException extends RuntimeException {

    private final PendingHandoffStatus currentStatus;

    public PendingHandoffStateConflictException(PendingHandoffStatus currentStatus) {
        super("Pending handoff state does not allow this manual action");
        this.currentStatus = currentStatus;
    }

    public PendingHandoffStatus getCurrentStatus() {
        return currentStatus;
    }
}
