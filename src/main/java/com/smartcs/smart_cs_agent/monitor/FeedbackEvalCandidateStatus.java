package com.smartcs.smart_cs_agent.monitor;

/**
 * Eval 候选样本的审核状态。
 *
 * 【为什么不用普通字符串？】
 * 审核状态会被前端展示，也会影响后续是否导出为正式评测样本。
 * 如果直接用字符串，容易出现 pending、pass、approved 等多种写法。
 * 枚举可以把状态收敛成固定集合，让统计和接口返回更稳定。
 */
public enum FeedbackEvalCandidateStatus {
    /** 刚由无用反馈生成，还没有人工确认 */
    PENDING_REVIEW,

    /** 已确认值得整理成正式 Eval case */
    APPROVED,

    /** 已确认不适合作为评测样本，例如误点或上下文不足 */
    REJECTED;

    /**
     * 解析前端传入的审核状态。
     *
     * 第一版只允许审核成 APPROVED 或 REJECTED，
     * 不允许前端把样本重新改回 PENDING_REVIEW，避免审核语义混乱。
     */
    public static FeedbackEvalCandidateStatus reviewTargetFrom(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Candidate status cannot be empty");
        }

        try {
            FeedbackEvalCandidateStatus status = FeedbackEvalCandidateStatus.valueOf(value.trim().toUpperCase());
            if (status == PENDING_REVIEW) {
                throw new IllegalArgumentException("Candidate status must be APPROVED or REJECTED");
            }
            return status;
        } catch (IllegalArgumentException exception) {
            if ("Candidate status must be APPROVED or REJECTED".equals(exception.getMessage())) {
                throw exception;
            }
            throw new IllegalArgumentException("Candidate status must be APPROVED or REJECTED");
        }
    }
}
