package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.monitor.FeedbackEvalCaseDraft;
import com.smartcs.smart_cs_agent.monitor.FeedbackEvalCandidate;
import com.smartcs.smart_cs_agent.monitor.FeedbackEvalCandidateStatus;
import com.smartcs.smart_cs_agent.monitor.FeedbackRating;
import com.smartcs.smart_cs_agent.monitor.FeedbackRecord;
import com.smartcs.smart_cs_agent.monitor.EvalCaseMergeResult;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.MonitorSummary;
import com.smartcs.smart_cs_agent.monitor.ResponseQualityEvalCase;
import com.smartcs.smart_cs_agent.monitor.ResponseQualityEvalCaseFileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitor 指标接口。
 *
 * 【第一版接口】
 * GET /api/monitor/summary
 *
 * 它返回的是当前 JVM 内存中的在线指标摘要。
 * 后续如果接 Prometheus、数据库或日志平台，可以保持这个 Controller 不变，只替换 MonitorService 的实现。
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService monitorService;
    private final ResponseQualityEvalCaseFileService evalCaseFileService;

    public MonitorController(MonitorService monitorService,
                             ResponseQualityEvalCaseFileService evalCaseFileService) {
        this.monitorService = monitorService;
        this.evalCaseFileService = evalCaseFileService;
    }

    @GetMapping("/summary")
    public MonitorSummary getSummary() {
        return monitorService.getSummary();
    }

    /**
     * 记录用户反馈。
     *
     * 【为什么放在 MonitorController？】
     * 用户反馈属于“在线表现数据”，和工具成功率、LLM 失败率一样，
     * 都是用来观察 Agent 表现的指标，所以第一版放在 Monitor 模块里最清晰。
     */
    @PostMapping("/feedback")
    public FeedbackResponse recordFeedback(@Valid @RequestBody(required = false) FeedbackRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Feedback request cannot be empty");
        }

        FeedbackRating rating = FeedbackRating.from(request.getRating());
        FeedbackRecord record = monitorService.recordFeedback(
                request.getConversationId(),
                request.getUserId(),
                rating,
                request.getReason(),
                request.getAssistantReply()
        );

        return new FeedbackResponse(true, "Feedback recorded", record);
    }

    /**
     * 查看由负反馈生成的 Eval 候选样本。
     *
     * 这个接口先服务面试演示和人工审核：用户点“无用”后，
     * 我们可以马上看到哪条回复值得补进离线评测集。
     */
    @GetMapping("/feedback/candidates")
    public List<FeedbackEvalCandidate> getFeedbackEvalCandidates() {
        return monitorService.getFeedbackEvalCandidates();
    }

    /**
     * 审核 Eval 候选样本。
     *
     * 第一版只支持 APPROVED / REJECTED 两种结果。
     * 通过审核的样本，后续可以导出并整理成正式 Eval case。
     */
    @PostMapping("/feedback/candidates/{candidateId}/review")
    public EvalCandidateReviewResponse reviewFeedbackEvalCandidate(@PathVariable String candidateId,
                                                                   @Valid @RequestBody(required = false)
                                                                   EvalCandidateReviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Review request cannot be empty");
        }

        FeedbackEvalCandidateStatus status = FeedbackEvalCandidateStatus.reviewTargetFrom(request.getStatus());
        FeedbackEvalCandidate candidate = monitorService.reviewFeedbackEvalCandidate(
                candidateId,
                status,
                request.getReviewNote()
        );

        return new EvalCandidateReviewResponse(true, "Eval candidate reviewed", candidate);
    }

    /**
     * 导出审核通过的 Eval case 草稿。
     *
     * 这个接口不会直接修改测试资源文件，只返回 JSON 草稿。
     * 这样可以避免把不完整样本自动写进正式评测集。
     */
    @GetMapping("/feedback/candidates/export")
    public List<FeedbackEvalCaseDraft> exportApprovedEvalCaseDrafts() {
        return monitorService.exportApprovedEvalCaseDrafts();
    }

    /**
     * 将人工补齐断言后的草稿合并到正式回复质量评测文件。
     *
     * 这里故意要求 sourceCandidateId 必须来自 APPROVED 草稿：
     * - 用户点击“无用”只说明有质量信号；
     * - APPROVED 说明开发者已经人工确认值得沉淀；
     * - merge 请求里的 requiredKeywords / forbiddenKeywords 说明开发者已经补齐评测断言。
     *
     * 这三步合在一起，才允许写入正式 response_quality_eval_cases.json。
     */
    @PostMapping("/feedback/candidates/merge")
    public EvalCaseMergeResponse mergeApprovedEvalCaseDrafts(@Valid @RequestBody(required = false)
                                                             EvalCaseMergeRequest request) {
        if (request == null || request.getCases().isEmpty()) {
            throw new IllegalArgumentException("Eval case merge request cannot be empty");
        }

        Map<String, FeedbackEvalCaseDraft> approvedDrafts = approvedDraftsByCandidateId();
        List<ResponseQualityEvalCase> cases = new ArrayList<>();
        for (EvalCaseMergeItem item : request.getCases()) {
            FeedbackEvalCaseDraft draft = approvedDraft(item, approvedDrafts);
            cases.add(toResponseQualityEvalCase(item, draft));
        }

        EvalCaseMergeResult result = evalCaseFileService.merge(cases);
        return new EvalCaseMergeResponse(true, "Eval cases merged", result);
    }

    private Map<String, FeedbackEvalCaseDraft> approvedDraftsByCandidateId() {
        Map<String, FeedbackEvalCaseDraft> drafts = new LinkedHashMap<>();
        for (FeedbackEvalCaseDraft draft : monitorService.exportApprovedEvalCaseDrafts()) {
            drafts.put(draft.getSourceCandidateId(), draft);
        }
        return drafts;
    }

    private FeedbackEvalCaseDraft approvedDraft(EvalCaseMergeItem item,
                                                Map<String, FeedbackEvalCaseDraft> approvedDrafts) {
        if (item == null) {
            throw new IllegalArgumentException("Eval case merge item cannot be empty");
        }
        if (item.getSourceCandidateId() == null || item.getSourceCandidateId().isBlank()) {
            throw new IllegalArgumentException("Source candidate id cannot be empty");
        }

        FeedbackEvalCaseDraft draft = approvedDrafts.get(item.getSourceCandidateId().trim());
        if (draft == null) {
            throw new IllegalArgumentException("Eval candidate is not approved or does not exist: " + item.getSourceCandidateId());
        }
        return draft;
    }

    private ResponseQualityEvalCase toResponseQualityEvalCase(EvalCaseMergeItem item,
                                                              FeedbackEvalCaseDraft draft) {
        String id = item.getId() == null || item.getId().isBlank()
                ? draft.getId()
                : item.getId().trim();
        String candidateReply = item.getCandidateReply() == null || item.getCandidateReply().isBlank()
                ? draft.getCandidateReply()
                : item.getCandidateReply().trim();
        List<String> requiredKeywords = item.getRequiredKeywords();
        List<String> forbiddenKeywords = item.getForbiddenKeywords().isEmpty()
                ? draft.getForbiddenKeywords()
                : item.getForbiddenKeywords();

        return new ResponseQualityEvalCase(
                id,
                candidateReply,
                requiredKeywords,
                forbiddenKeywords,
                item.isExpectedPass()
        );
    }
}
