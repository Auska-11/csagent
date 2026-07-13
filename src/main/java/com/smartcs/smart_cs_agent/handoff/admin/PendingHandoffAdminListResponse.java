package com.smartcs.smart_cs_agent.handoff.admin;

import java.util.List;

/** HTTP 列表页；nextCursor 是不透明 Base64URL 文本，客户端不应解析其内容。 */
public record PendingHandoffAdminListResponse(
        List<PendingHandoffAdminListItemResponse> items,
        String nextCursor,
        boolean hasMore
) {

    public PendingHandoffAdminListResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
