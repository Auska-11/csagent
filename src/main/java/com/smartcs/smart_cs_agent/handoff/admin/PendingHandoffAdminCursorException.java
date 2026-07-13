package com.smartcs.smart_cs_agent.handoff.admin;

/** 不透明 HTTP 游标损坏、版本不兼容或被用于另一种筛选条件。 */
public class PendingHandoffAdminCursorException extends RuntimeException {

    public PendingHandoffAdminCursorException() {
        super("Pending handoff cursor is invalid");
    }
}
