package com.smartcs.smart_cs_agent.handoff.review;

/**
 * 只读人工处置审计端口。
 *
 * <p>查询必须同时带 tenantId 与 outboxId：outboxId 即使不可猜也不是授权边界，Adapter 仍要用租户复合
 * 条件查询，跨租户与不存在记录都返回相同的空结果。</p>
 */
public interface PendingHandoffReviewAuditStore {

    /**
     * 按 occurredAt DESC、auditId DESC 查询一条 Outbox 的稳定时间线。
     * 实现应把 limit 限制在 1..100，防止管理接口无界读取。
     */
    PendingHandoffReviewAuditPage findAuditPage(String tenantId,
                                                String outboxId,
                                                PendingHandoffReviewAuditCursor cursor,
                                                int limit);
}
