package com.smartcs.smart_cs_agent.persistence.mybatis.row;

public record PendingHandoffAuditRow(
        String auditId,
        String tenantId,
        String outboxId,
        String action,
        String actorId,
        String authorityUsed,
        String reasonCode,
        String decisionNote,
        String evidenceSource,
        String fromStatus,
        String toStatus,
        long fromVersion,
        long toVersion,
        String reviewFailureType,
        int attemptCountAtDecision,
        Long scheduledAt,
        long occurredAt
) {
}
