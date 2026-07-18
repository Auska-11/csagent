package com.smartcs.smart_cs_agent.persistence.mybatis.row;

/** cs_pending_handoff 的完整持久化投影。 */
public record PendingHandoffRow(
        String outboxId,
        String tenantId,
        String idempotencyKey,
        String requestFingerprintHash,
        String userId,
        String conversationId,
        String reason,
        String summaryText,
        String requestTitle,
        String requestDescription,
        String customerId,
        String productLine,
        String issueType,
        String priorityCode,
        String status,
        String failureType,
        int attemptCount,
        long nextAttemptAt,
        long version,
        long createdAt,
        long updatedAt
) {
}
