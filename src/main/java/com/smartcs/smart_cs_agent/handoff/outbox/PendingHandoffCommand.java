package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;

import java.util.Objects;

/**
 * 写入人工转接 Outbox 的命令。
 *
 * <p>除了远端请求，还保存本地用户、会话和转接原因，便于后续人工核对。这个命令会在外部调用
 * 之前写入，因此不包含失败信息；失败类型由后续状态更新单独记录。</p>
 */
public record PendingHandoffCommand(
        String userId,
        String conversationId,
        String reason,
        String summary,
        AfterSalesTicketCreateRequest request
) {

    public PendingHandoffCommand {
        requireText(userId, "userId");
        requireText(conversationId, "conversationId");
        requireText(reason, "reason");
        requireText(summary, "summary");
        Objects.requireNonNull(request, "request cannot be null");
        requireText(request.idempotencyKey(), "request.idempotencyKey");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }
}
