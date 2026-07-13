package com.smartcs.smart_cs_agent.handoff.admin;

import java.util.List;

/** 一条 Outbox 的审计时间线页面。 */
public record PendingHandoffAdminAuditPageResponse(
        List<PendingHandoffAdminAuditEventResponse> events,
        String nextCursor,
        boolean hasMore
) {

    public PendingHandoffAdminAuditPageResponse {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
