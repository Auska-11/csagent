package com.smartcs.smart_cs_agent.handoff.review;

/**
 * 当前租户内不存在指定人工转接记录。
 *
 * <p>异常不保存 tenantId 或 outboxId，避免被通用日志意外打印；HTTP 层会把不存在和跨租户统一成 404。</p>
 */
public class PendingHandoffReviewNotFoundException extends RuntimeException {

    public PendingHandoffReviewNotFoundException() {
        super("Pending handoff review record was not found");
    }
}
