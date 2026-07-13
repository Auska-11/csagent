package com.smartcs.smart_cs_agent.handoff.review;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.security.SensitiveTextRedactor;

import java.util.Objects;

/**
 * 一次人工处置生成的不可变、只追加审计事件。
 *
 * <p>事件同时保存变更前后状态与版本。数据库实现必须把“主记录 CAS + INSERT 审计事件”放进同一事务：
 * CAS 失败时不能留下实际上没有发生的审计记录，审计插入失败时也不能只改变业务状态。</p>
 *
 * <p>构造器拒绝不可能的动作组合，保证 Memory 与 JDBC Adapter 即使实现方式不同，也无法写出
 * “CLOSE 却变成 CONFIRMED”或“没有证据却人工确认成功”的历史。</p>
 */
public record PendingHandoffReviewAuditEvent(
        String auditId,
        String tenantId,
        String outboxId,
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
        String reviewFailureType,
        int attemptCountAtDecision,
        Long scheduledAt,
        long occurredAt
) {

    private static final int AUDIT_ID_MAX_CODE_POINTS = 64;
    private static final int FAILURE_TYPE_MAX_CODE_POINTS = 128;

    public PendingHandoffReviewAuditEvent {
        auditId = PendingHandoffManualDecisionContext.requireTrimmedText(
                auditId,
                "auditId",
                AUDIT_ID_MAX_CODE_POINTS
        );
        tenantId = PendingHandoffManualDecisionContext.requireTrimmedText(
                tenantId,
                "tenantId",
                PendingHandoffManualDecisionContext.TENANT_ID_MAX_CODE_POINTS
        );
        outboxId = PendingHandoffManualDecisionContext.requireTrimmedText(
                outboxId,
                "outboxId",
                PendingHandoffManualDecisionContext.OUTBOX_ID_MAX_CODE_POINTS
        );
        Objects.requireNonNull(action, "action cannot be null");
        actorId = PendingHandoffManualDecisionContext.requireTrimmedText(
                actorId,
                "actorId",
                PendingHandoffManualDecisionContext.ACTOR_ID_MAX_CODE_POINTS
        );
        Objects.requireNonNull(authorityUsed, "authorityUsed cannot be null");
        reasonCode = PendingHandoffManualDecisionContext.normalizeReasonCode(reasonCode);
        decisionNote = SensitiveTextRedactor.sanitizeSingleLine(
                decisionNote,
                PendingHandoffManualDecisionContext.DECISION_NOTE_MAX_CODE_POINTS
        );
        Objects.requireNonNull(evidenceSource, "evidenceSource cannot be null");
        Objects.requireNonNull(fromStatus, "fromStatus cannot be null");
        Objects.requireNonNull(toStatus, "toStatus cannot be null");
        if (fromStatus != PendingHandoffStatus.NEEDS_MANUAL_REVIEW) {
            throw new IllegalArgumentException(
                    "Manual decisions must start from NEEDS_MANUAL_REVIEW"
            );
        }
        if (fromVersion < 0 || fromVersion == Long.MAX_VALUE || toVersion != fromVersion + 1) {
            throw new IllegalArgumentException("toVersion must equal fromVersion + 1");
        }
        reviewFailureType = PendingHandoffManualDecisionContext.requireTrimmedText(
                reviewFailureType,
                "reviewFailureType",
                FAILURE_TYPE_MAX_CODE_POINTS
        );
        if (attemptCountAtDecision < 0) {
            throw new IllegalArgumentException("attemptCountAtDecision cannot be negative");
        }
        if (scheduledAt != null && scheduledAt < 0) {
            throw new IllegalArgumentException("scheduledAt cannot be negative");
        }
        if (occurredAt < 0) {
            throw new IllegalArgumentException("occurredAt cannot be negative");
        }

        validateActionCombination(action, evidenceSource, toStatus, scheduledAt);
    }

    private static void validateActionCombination(PendingHandoffManualAction action,
                                                  PendingHandoffEvidenceSource evidenceSource,
                                                  PendingHandoffStatus toStatus,
                                                  Long scheduledAt) {
        switch (action) {
            case RETRY -> {
                if (toStatus != PendingHandoffStatus.RETRY_SCHEDULED
                        || evidenceSource != PendingHandoffEvidenceSource.NONE
                        || scheduledAt == null) {
                    throw new IllegalArgumentException(
                            "RETRY requires RETRY_SCHEDULED, evidence NONE and scheduledAt"
                    );
                }
            }
            case CONFIRM -> {
                if (toStatus != PendingHandoffStatus.CONFIRMED
                        || evidenceSource == PendingHandoffEvidenceSource.NONE
                        || scheduledAt != null) {
                    throw new IllegalArgumentException(
                            "CONFIRM requires CONFIRMED, verifiable evidence and no scheduledAt"
                    );
                }
            }
            case CLOSE -> {
                if (toStatus != PendingHandoffStatus.MANUALLY_CLOSED
                        || evidenceSource != PendingHandoffEvidenceSource.NONE
                        || scheduledAt != null) {
                    throw new IllegalArgumentException(
                            "CLOSE requires MANUALLY_CLOSED, evidence NONE and no scheduledAt"
                    );
                }
            }
        }
    }

    /**
     * 默认 record.toString() 会打印全部字段，可能把租户、记录主键、管理员账号和自由备注写入日志。
     * 这里仅保留排障需要的状态机坐标；需要查看完整内容必须通过受控审计查询接口。
     */
    @Override
    public String toString() {
        return "PendingHandoffReviewAuditEvent["
                + "auditId=" + auditId
                + ", action=" + action
                + ", authorityUsed=" + authorityUsed
                + ", evidenceSource=" + evidenceSource
                + ", fromStatus=" + fromStatus
                + ", toStatus=" + toStatus
                + ", fromVersion=" + fromVersion
                + ", toVersion=" + toVersion
                + ", attemptCountAtDecision=" + attemptCountAtDecision
                + ", occurredAt=" + occurredAt
                + ']';
    }
}
