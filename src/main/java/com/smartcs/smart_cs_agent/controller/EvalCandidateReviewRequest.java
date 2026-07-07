package com.smartcs.smart_cs_agent.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Eval 候选样本审核请求体。
 *
 * 审核动作来自前端，所以仍然用 DTO 做边界隔离：
 * Controller 负责接收和校验外部输入，MonitorService 只处理已经明确的业务状态。
 */
public class EvalCandidateReviewRequest {

    /** 审核目标状态：APPROVED 或 REJECTED */
    @NotBlank(message = "Status cannot be empty")
    @Size(max = 32, message = "Status is too long")
    private String status;

    /** 审核备注，可用于说明为什么通过或拒绝 */
    @Size(max = 300, message = "Review note is too long")
    private String reviewNote;

    public EvalCandidateReviewRequest() {
    }

    public EvalCandidateReviewRequest(String status, String reviewNote) {
        this.status = status;
        this.reviewNote = reviewNote;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }
}
