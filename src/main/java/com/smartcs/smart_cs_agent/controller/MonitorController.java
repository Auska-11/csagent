package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.monitor.FeedbackEvalCandidate;
import com.smartcs.smart_cs_agent.monitor.FeedbackRating;
import com.smartcs.smart_cs_agent.monitor.FeedbackRecord;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.MonitorSummary;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
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
}
