package com.smartcs.smart_cs_agent.handoff.admin;

import java.util.Objects;

/** 原子人工决定的 HTTP 投影：更新后详情与同事务审计事件必须同时存在。 */
public record PendingHandoffAdminDecisionResponse(
        PendingHandoffAdminDetailResponse record,
        PendingHandoffAdminAuditEventResponse auditEvent
) {

    public PendingHandoffAdminDecisionResponse {
        Objects.requireNonNull(record, "record cannot be null");
        Objects.requireNonNull(auditEvent, "auditEvent cannot be null");
    }
}
