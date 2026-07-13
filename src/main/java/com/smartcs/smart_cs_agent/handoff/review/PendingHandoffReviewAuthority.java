package com.smartcs.smart_cs_agent.handoff.review;

/**
 * 执行人工复核决定时实际使用的授权级别。
 *
 * <p>20.3 只有 ADMIN。仍然把它写入审计事件，是为了以后新增审计专员或自动策略时不必猜测历史操作
 * 当时使用了哪一种权限。</p>
 */
public enum PendingHandoffReviewAuthority {
    ADMIN
}
