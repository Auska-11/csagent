package com.smartcs.smart_cs_agent.handoff.admin;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;

/**
 * 单条复核详情的最小化 DTO。
 *
 * <p>完整 request、tenantId、userId、conversationId、customerId 和 idempotencyKey 永远不进入该对象。</p>
 */
public record PendingHandoffAdminDetailResponse(
        String outboxId,
        PendingHandoffStatus status,
        // 与审计事件的 reasonCode 区分：这里描述原始转人工原因，后者描述管理员的处置理由。
        String handoffReasonCode,
        String failureCode,
        String summaryPreview,
        String productLineCode,
        String issueTypeCode,
        String priorityCode,
        PendingHandoffAdminTicketResponse confirmedTicket,
        int attemptCount,
        Long scheduledAt,
        long version,
        long createdAt,
        long updatedAt
) {
}
