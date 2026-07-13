package com.smartcs.smart_cs_agent.handoff.review;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;

import java.util.List;

/** Store 返回的人工复核 keyset 分页结果。 */
public record PendingHandoffReviewPage(
        List<PendingHandoffRecord> records,
        PendingHandoffReviewCursor nextCursor,
        boolean hasMore
) {

    public PendingHandoffReviewPage {
        records = records == null ? List.of() : List.copyOf(records);
        if (hasMore && (records.isEmpty() || nextCursor == null)) {
            throw new IllegalArgumentException("A page with more records requires a next cursor");
        }
        if (!hasMore && nextCursor != null) {
            throw new IllegalArgumentException("A final page cannot expose a next cursor");
        }
    }
}
