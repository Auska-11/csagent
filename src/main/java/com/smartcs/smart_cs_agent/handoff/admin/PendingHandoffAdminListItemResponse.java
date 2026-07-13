package com.smartcs.smart_cs_agent.handoff.admin;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;

/**
 * 批量复核列表中的安全摘要。
 *
 * <p>列表刻意不含用户摘要、请求内容和身份字段，避免一次请求读取大量自由文本。</p>
 */
public record PendingHandoffAdminListItemResponse(
        String outboxId,
        PendingHandoffStatus status,
        // 这是“触发转人工”的原因，不是管理员提交 retry/confirm/close 时填写的处置原因。
        String handoffReasonCode,
        String failureCode,
        int attemptCount,
        Long scheduledAt,
        long version,
        long createdAt,
        long updatedAt
) {
}
