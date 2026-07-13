package com.smartcs.smart_cs_agent.handoff.review;

import java.util.List;

/** Store 返回的一页只读人工处置审计事件。 */
public record PendingHandoffReviewAuditPage(
        List<PendingHandoffReviewAuditEvent> events,
        PendingHandoffReviewAuditCursor nextCursor,
        boolean hasMore
) {

    public PendingHandoffReviewAuditPage {
        // List.copyOf 同时防御调用方后续修改原集合，也拒绝含 null 的伪事件。
        events = events == null ? List.of() : List.copyOf(events);
        if (hasMore && (events.isEmpty() || nextCursor == null)) {
            throw new IllegalArgumentException("A page with more events requires a next cursor");
        }
        if (!hasMore && nextCursor != null) {
            throw new IllegalArgumentException("A final page cannot expose a next cursor");
        }
    }
}
