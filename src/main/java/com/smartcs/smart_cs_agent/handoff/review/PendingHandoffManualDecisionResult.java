package com.smartcs.smart_cs_agent.handoff.review;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;

import java.util.Objects;

/**
 * 原子人工处置的两个结果：更新后的业务记录和同事务追加的审计事件。
 *
 * <p>把二者作为一个返回值，可以提醒调用方这不是两个可独立成功的写操作。</p>
 */
public record PendingHandoffManualDecisionResult(
        PendingHandoffRecord record,
        PendingHandoffReviewAuditEvent auditEvent
) {

    public PendingHandoffManualDecisionResult {
        Objects.requireNonNull(record, "record cannot be null");
        Objects.requireNonNull(auditEvent, "auditEvent cannot be null");
        if (!Objects.equals(record.tenantId(), auditEvent.tenantId())
                || !Objects.equals(record.outboxId(), auditEvent.outboxId())
                || record.status() != auditEvent.toStatus()
                || record.version() != auditEvent.toVersion()
                || record.attemptCount() != auditEvent.attemptCountAtDecision()) {
            throw new IllegalArgumentException(
                    "record and auditEvent must describe the same committed decision"
            );
        }
    }

    /**
     * PendingHandoffRecord 含用户摘要和完整售后请求，不能通过结果对象的默认 toString 间接进入日志。
     */
    @Override
    public String toString() {
        return "PendingHandoffManualDecisionResult["
                + "status=" + record.status()
                + ", version=" + record.version()
                + ", action=" + auditEvent.action()
                + ", auditId=" + auditEvent.auditId()
                + ']';
    }
}
