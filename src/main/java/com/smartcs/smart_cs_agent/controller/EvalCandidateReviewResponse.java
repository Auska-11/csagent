package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.monitor.FeedbackEvalCandidate;

/**
 * Eval 候选样本审核响应体。
 *
 * 返回审核后的完整候选样本，前端可以直接刷新当前行的状态和备注。
 */
public class EvalCandidateReviewResponse {

    private final boolean success;
    private final String message;
    private final FeedbackEvalCandidate candidate;

    public EvalCandidateReviewResponse(boolean success, String message, FeedbackEvalCandidate candidate) {
        this.success = success;
        this.message = message;
        this.candidate = candidate;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public FeedbackEvalCandidate getCandidate() {
        return candidate;
    }
}
