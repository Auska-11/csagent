package com.smartcs.smart_cs_agent.handoff.admin;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffEvidenceSource;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualAction;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuthority;

/** 受 ADMIN 和租户边界保护的审计事件 DTO。 */
public record PendingHandoffAdminAuditEventResponse(
        String auditId,
        PendingHandoffManualAction action,
        String actorId,
        PendingHandoffReviewAuthority authorityUsed,
        String reasonCode,
        String decisionNote,
        PendingHandoffEvidenceSource evidenceSource,
        PendingHandoffStatus fromStatus,
        PendingHandoffStatus toStatus,
        long fromVersion,
        long toVersion,
        String reviewFailureCode,
        int attemptCountAtDecision,
        Long scheduledAt,
        long occurredAt
) {
}
