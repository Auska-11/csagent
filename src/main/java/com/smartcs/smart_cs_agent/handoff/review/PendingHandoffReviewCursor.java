package com.smartcs.smart_cs_agent.handoff.review;

/**
 * 人工复核列表的结构化游标。
 *
 * <p>Store 只认识排序字段，不认识 HTTP 层将来使用的 Base64 文本。这样数据库端口不会依赖
 * Controller 的编码格式，也便于 memory/JDBC 两种实现复用同一分页语义。</p>
 */
public record PendingHandoffReviewCursor(long updatedAt, String outboxId) {

    public PendingHandoffReviewCursor {
        if (updatedAt < 0) {
            throw new IllegalArgumentException("updatedAt cannot be negative");
        }
        if (outboxId == null || outboxId.isBlank()) {
            throw new IllegalArgumentException("outboxId cannot be blank");
        }
        outboxId = outboxId.trim();
    }
}
