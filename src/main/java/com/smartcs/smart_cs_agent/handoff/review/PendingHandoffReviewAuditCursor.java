package com.smartcs.smart_cs_agent.handoff.review;

/**
 * 审计时间线的 keyset 分页游标。
 *
 * <p>{@code occurredAt + auditId} 可以在多个事件毫秒时间戳相同时保持稳定顺序；不能只使用 offset，
 * 因为管理员翻页期间追加的新事件会让 offset 页面发生重复或遗漏。</p>
 */
public record PendingHandoffReviewAuditCursor(long occurredAt, String auditId) {

    public PendingHandoffReviewAuditCursor {
        if (occurredAt < 0) {
            throw new IllegalArgumentException("occurredAt cannot be negative");
        }
        auditId = PendingHandoffManualDecisionContext.requireTrimmedText(
                auditId,
                "auditId",
                64
        );
    }
}
