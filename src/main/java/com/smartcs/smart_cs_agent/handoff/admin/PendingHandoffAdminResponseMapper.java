package com.smartcs.smart_cs_agent.handoff.admin;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionResult;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditEvent;
import com.smartcs.smart_cs_agent.security.SensitiveTextRedactor;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 领域对象到 ADMIN HTTP DTO 的唯一数据最小化出口。
 *
 * <p>Controller 不得直接返回 PendingHandoffRecord。把投影集中在这里，新增领域字段时默认不会自动进入
 * JSON，必须经过一次明确的安全评审。</p>
 */
@Component
public class PendingHandoffAdminResponseMapper {

    private static final Set<String> ALLOWED_HANDOFF_REASONS = Set.of(
            "USER_REQUESTED_HUMAN",
            "COMPLAINT",
            "COMPENSATION_OR_DISPUTE"
    );

    public PendingHandoffAdminListItemResponse toListItem(PendingHandoffRecord record) {
        return new PendingHandoffAdminListItemResponse(
                safeIdentifier(record.outboxId(), 64, "UNKNOWN"),
                record.status(),
                safeHandoffReason(record.reason()),
                safeIdentifier(record.failureType(), 128, "UNKNOWN"),
                record.attemptCount(),
                visibleSchedule(record.nextAttemptAt()),
                record.version(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    public PendingHandoffAdminDetailResponse toDetail(PendingHandoffRecord record) {
        AfterSalesTicketCreateRequest request = record.request();
        return new PendingHandoffAdminDetailResponse(
                safeIdentifier(record.outboxId(), 64, "UNKNOWN"),
                record.status(),
                safeHandoffReason(record.reason()),
                safeIdentifier(record.failureType(), 128, "UNKNOWN"),
                SensitiveTextRedactor.sanitizeSingleLine(record.summary(), 240),
                request == null ? "UNKNOWN" : safeIdentifier(request.productLine(), 64, "UNKNOWN"),
                request == null ? "UNKNOWN" : safeIdentifier(request.issueType(), 64, "UNKNOWN"),
                request == null ? "UNKNOWN" : safeIdentifier(request.priority(), 32, "UNKNOWN"),
                toTicket(record.confirmedResult()),
                record.attemptCount(),
                visibleSchedule(record.nextAttemptAt()),
                record.version(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    public PendingHandoffAdminAuditEventResponse toAuditEvent(PendingHandoffReviewAuditEvent event) {
        return new PendingHandoffAdminAuditEventResponse(
                safeIdentifier(event.auditId(), 64, "UNKNOWN"),
                event.action(),
                safeIdentifier(event.actorId(), 128, "UNKNOWN"),
                event.authorityUsed(),
                safeIdentifier(event.reasonCode(), 64, "UNKNOWN"),
                SensitiveTextRedactor.sanitizeSingleLine(event.decisionNote(), 256),
                event.evidenceSource(),
                event.fromStatus(),
                event.toStatus(),
                event.fromVersion(),
                event.toVersion(),
                safeIdentifier(event.reviewFailureType(), 128, "UNKNOWN"),
                event.attemptCountAtDecision(),
                event.scheduledAt(),
                event.occurredAt()
        );
    }

    public PendingHandoffAdminDecisionResponse toDecision(PendingHandoffManualDecisionResult result) {
        return new PendingHandoffAdminDecisionResponse(
                toDetail(result.record()),
                toAuditEvent(result.auditEvent())
        );
    }

    private PendingHandoffAdminTicketResponse toTicket(AfterSalesTicketCreateResult result) {
        if (result == null) {
            return null;
        }
        return new PendingHandoffAdminTicketResponse(
                safeIdentifier(result.ticketId(), 128, "UNKNOWN"),
                safeIdentifier(result.status(), 64, "UNKNOWN"),
                SensitiveTextRedactor.sanitizeSingleLine(result.createdAt(), 128)
        );
    }

    private Long visibleSchedule(long nextAttemptAt) {
        return nextAttemptAt == 0 || nextAttemptAt == Long.MAX_VALUE ? null : nextAttemptAt;
    }

    private String safeHandoffReason(String value) {
        String candidate = safeIdentifier(value, 64, "OTHER");
        return ALLOWED_HANDOFF_REASONS.contains(candidate) ? candidate : "OTHER";
    }

    private String safeIdentifier(String value, int maxCodePoints, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String compact = value.trim().replaceAll("[^A-Za-z0-9_$.-]", "_");
        int count = compact.codePointCount(0, compact.length());
        if (count <= maxCodePoints) {
            return compact;
        }
        return compact.substring(0, compact.offsetByCodePoints(0, maxCodePoints));
    }
}
