package com.smartcs.smart_cs_agent.handoff.admin;

/** status、limit、路径 ID 或确认结果等 ADMIN API 参数不符合领域边界。 */
public class PendingHandoffAdminRequestException extends RuntimeException {

    public PendingHandoffAdminRequestException() {
        super("Pending handoff review request is invalid");
    }
}
