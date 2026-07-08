package com.smartcs.smart_cs_agent.monitor;

import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 在线监控指标服务。
 *
 * 【为什么从 Trace 统计？】
 * 我们已经在 AgentRuntime 里记录了工具调用、LLM 调用、人工转接和耗时。
 * Monitor 第一版直接消费 Trace，不需要再侵入 Runtime。
 *
 * 这样做有两个好处：
 * 1. Runtime 继续专注“执行 Agent”，Monitor 专注“观察 Agent”。
 * 2. 面试时可以讲清楚：Trace 是底层事实日志，Monitor 是基于 Trace 的聚合视图。
 */
@Service
public class MonitorService {

    /** 最近只保留 10 轮摘要，避免内存无限增长 */
    private static final int RECENT_TURN_LIMIT = 10;

    /** 最近只保留 10 条用户反馈，避免调试面板无限变长 */
    private static final int RECENT_FEEDBACK_LIMIT = 10;

    /** 最近只保留 10 条 Eval 候选样本，第一版先服务前端调试面板 */
    private static final int RECENT_EVAL_CANDIDATE_LIMIT = 10;

    /** 助手回复可能很长，Monitor 里只保留适合调试和人工审核的长度 */
    private static final int ASSISTANT_REPLY_LIMIT = 1000;

    /** 导出负反馈草稿时默认携带的危险词，开发者后续可以在正式 case 中调整 */
    private static final List<String> DEFAULT_FORBIDDEN_KEYWORDS = List.of(
            "一定可以退款",
            "保证退款",
            "无条件退款",
            "一定赔偿",
            "保证赔偿",
            "Tool",
            "tool_"
    );

    private long totalTurns;
    private long toolCallCount;
    private long failedToolCallCount;
    private long llmCallCount;
    private long llmFailureCount;
    private long handoffCount;
    private long feedbackCount;
    private long helpfulFeedbackCount;
    private long unhelpfulFeedbackCount;
    private long feedbackSequence;
    private long evalCandidateSequence;
    private long evalCandidateCount;
    private long totalToolDurationMs;
    private long toolDurationSamples;
    private long totalLlmDurationMs;
    private long llmDurationSamples;
    private long lastUpdatedAt;

    private final FeedbackEvalCandidateStore candidateStore;
    private final Deque<MonitorTurnSnapshot> recentTurns = new ArrayDeque<>();
    private final Deque<FeedbackRecord> recentFeedbacks = new ArrayDeque<>();
    private final Deque<FeedbackEvalCandidate> recentEvalCandidates = new ArrayDeque<>();
    private final Map<String, ToolAccumulator> toolStats = new LinkedHashMap<>();

    public MonitorService() {
        this(FeedbackEvalCandidateStore.disabled());
    }

    @Autowired
    public MonitorService(FeedbackEvalCandidateStore candidateStore) {
        this.candidateStore = candidateStore == null ? FeedbackEvalCandidateStore.disabled() : candidateStore;
        restoreEvalCandidateState(this.candidateStore.load());
    }

    /**
     * 记录一轮聊天响应。
     *
     * 注意：这个方法用 synchronized，是因为 Spring 单例 Service 可能被多个请求同时调用。
     * 第一版数据量很小，用 synchronized 足够清晰；后续接真实流量时可以改成 AtomicLong 或指标系统。
     */
    public synchronized void recordTurn(ChatResponse response) {
        if (response == null) {
            return;
        }

        TurnAccumulator turn = new TurnAccumulator();
        for (TraceEvent event : response.getTrace()) {
            collectEvent(event, turn);
        }

        totalTurns++;
        toolCallCount += turn.toolCalls;
        failedToolCallCount += turn.failedToolCalls;
        llmCallCount += turn.llmCalls;
        llmFailureCount += turn.llmFailures;
        handoffCount += turn.handoffCount;
        totalToolDurationMs += turn.toolDurationMs;
        toolDurationSamples += turn.toolDurationSamples;
        totalLlmDurationMs += turn.llmDurationMs;
        llmDurationSamples += turn.llmDurationSamples;
        lastUpdatedAt = System.currentTimeMillis();

        recentTurns.addFirst(new MonitorTurnSnapshot(
                response.getConversationId(),
                response.getUserId(),
                response.getTimestamp(),
                turn.toolCalls,
                turn.failedToolCalls,
                turn.llmCalls,
                turn.llmFailures,
                turn.handoffCount,
                turn.toolDurationMs + turn.llmDurationMs
        ));

        while (recentTurns.size() > RECENT_TURN_LIMIT) {
            recentTurns.removeLast();
        }
    }

    /**
     * 获取当前聚合指标。
     *
     * 这个对象会直接序列化成 JSON，供前端 Monitor 面板展示。
     */
    public synchronized MonitorSummary getSummary() {
        long successfulToolCallCount = Math.max(0, toolCallCount - failedToolCallCount);
        double toolSuccessRate = rate(successfulToolCallCount, toolCallCount);
        double llmFailureRate = rate(llmFailureCount, llmCallCount);
        double helpfulFeedbackRate = rate(helpfulFeedbackCount, feedbackCount);
        long averageToolDurationMs = average(totalToolDurationMs, toolDurationSamples);
        long averageLlmDurationMs = average(totalLlmDurationMs, llmDurationSamples);
        List<MonitorTurnSnapshot> recent = new ArrayList<>(recentTurns);
        List<FeedbackRecord> feedbacks = new ArrayList<>(recentFeedbacks);
        List<FeedbackEvalCandidate> candidates = new ArrayList<>(recentEvalCandidates);
        List<ToolMonitorSnapshot> tools = buildToolStats();
        long pendingEvalCandidateCount = countEvalCandidates(candidates, FeedbackEvalCandidateStatus.PENDING_REVIEW);
        long approvedEvalCandidateCount = countEvalCandidates(candidates, FeedbackEvalCandidateStatus.APPROVED);
        long rejectedEvalCandidateCount = countEvalCandidates(candidates, FeedbackEvalCandidateStatus.REJECTED);
        Map<String, Long> issueCounts = buildEvalCandidateIssueCounts(candidates);
        List<MonitorAlert> alerts = buildAlerts(
                toolSuccessRate,
                llmFailureRate,
                helpfulFeedbackRate,
                pendingEvalCandidateCount,
                issueCounts
        );

        return new MonitorSummary(
                totalTurns,
                toolCallCount,
                successfulToolCallCount,
                failedToolCallCount,
                toolSuccessRate,
                llmCallCount,
                llmFailureCount,
                llmFailureRate,
                handoffCount,
                feedbackCount,
                helpfulFeedbackCount,
                unhelpfulFeedbackCount,
                helpfulFeedbackRate,
                averageToolDurationMs,
                averageLlmDurationMs,
                lastUpdatedAt,
                recent,
                tools,
                feedbacks,
                evalCandidateCount,
                pendingEvalCandidateCount,
                approvedEvalCandidateCount,
                rejectedEvalCandidateCount,
                issueCounts,
                candidates,
                alerts
        );
    }

    /**
     * 记录用户对某次回复的反馈。
     *
     * 注意：
     * - 反馈发生在 Agent 已经回复之后，所以不放进 AgentRuntime。
     * - 当前只做内存统计，后续可以把 FeedbackRecord 写入数据库或消息队列。
     */
    public synchronized FeedbackRecord recordFeedback(String conversationId,
                                                      String userId,
                                                      FeedbackRating rating,
                                                      String reason) {
        return recordFeedback(conversationId, userId, rating, reason, "");
    }

    /**
     * 记录用户对某次回复的反馈，并在负反馈时自动生成 Eval 候选样本。
     *
     * 【为什么负反馈生成候选，而不是直接写入正式评测集？】
     * 正式评测集需要稳定、可复现、含有明确期望；用户反馈只是线上信号。
     * 所以这里先沉淀为 PENDING_REVIEW，后续由开发者人工确认再进入正式 Eval case。
     */
    public synchronized FeedbackRecord recordFeedback(String conversationId,
                                                      String userId,
                                                      FeedbackRating rating,
                                                      String reason,
                                                      String assistantReply) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("ConversationId cannot be empty");
        }
        if (rating == null) {
            throw new IllegalArgumentException("Rating cannot be empty");
        }

        String actualUserId = (userId == null || userId.isBlank()) ? "anonymous" : userId.trim();
        String actualReason = reason == null ? "" : reason.trim();
        String actualAssistantReply = trimToLimit(assistantReply, ASSISTANT_REPLY_LIMIT);
        long now = System.currentTimeMillis();

        FeedbackRecord record = new FeedbackRecord(
                nextFeedbackId(),
                conversationId.trim(),
                actualUserId,
                rating,
                actualReason,
                actualAssistantReply,
                now
        );

        feedbackCount++;
        if (rating.isHelpful()) {
            helpfulFeedbackCount++;
        } else {
            unhelpfulFeedbackCount++;
        }
        lastUpdatedAt = now;

        recentFeedbacks.addFirst(record);
        while (recentFeedbacks.size() > RECENT_FEEDBACK_LIMIT) {
            recentFeedbacks.removeLast();
        }

        if (!rating.isHelpful()) {
            createEvalCandidate(record);
        }

        return record;
    }

    /**
     * 返回最近的 Eval 候选样本。
     *
     * 第一版先直接给前端看，后续可以把这里替换成数据库分页查询。
     */
    public synchronized List<FeedbackEvalCandidate> getFeedbackEvalCandidates() {
        return new ArrayList<>(recentEvalCandidates);
    }

    /**
     * 审核一条 Eval 候选样本。
     *
     * 【为什么审核后不删除候选样本？】
     * 审核结果本身也是质量闭环的一部分：
     * - APPROVED 表示这条线上问题值得进入正式评测集；
     * - REJECTED 表示这条负反馈暂时不适合做评测样本。
     *
     * 保留审核状态可以让前端和后续导出逻辑知道每条样本的去向。
     */
    public synchronized FeedbackEvalCandidate reviewFeedbackEvalCandidate(String candidateId,
                                                                          FeedbackEvalCandidateStatus status,
                                                                          String reviewNote) {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException("CandidateId cannot be empty");
        }
        if (status == null || status == FeedbackEvalCandidateStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Candidate status must be APPROVED or REJECTED");
        }

        Deque<FeedbackEvalCandidate> updated = new ArrayDeque<>();
        FeedbackEvalCandidate reviewed = null;
        long now = System.currentTimeMillis();

        for (FeedbackEvalCandidate candidate : recentEvalCandidates) {
            if (candidate.getCandidateId().equals(candidateId.trim())) {
                reviewed = candidate.reviewedAs(status, reviewNote, now);
                updated.addLast(reviewed);
            } else {
                updated.addLast(candidate);
            }
        }

        if (reviewed == null) {
            throw new IllegalArgumentException("Eval candidate not found: " + candidateId);
        }

        recentEvalCandidates.clear();
        recentEvalCandidates.addAll(updated);
        lastUpdatedAt = now;
        persistEvalCandidateState();
        return reviewed;
    }

    /**
     * 导出审核通过的 Eval case 草稿。
     *
     * 当前只导出 APPROVED 样本，REJECTED 和 PENDING_REVIEW 都不会进入草稿。
     * 这能保证后续评测集只吸收人工确认过的线上问题。
     */
    public synchronized List<FeedbackEvalCaseDraft> exportApprovedEvalCaseDrafts() {
        List<FeedbackEvalCaseDraft> drafts = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (FeedbackEvalCandidate candidate : recentEvalCandidates) {
            if (candidate.getStatus() == FeedbackEvalCandidateStatus.APPROVED) {
                drafts.add(toEvalCaseDraft(candidate, now));
            }
        }

        return drafts;
    }

    private void collectEvent(TraceEvent event, TurnAccumulator turn) {
        if (event == null || event.getEventType() == null) {
            return;
        }

        String eventType = event.getEventType();
        Map<String, Object> data = event.getData();

        switch (eventType) {
            case "tool_observed" -> recordToolResult(data, turn, asBoolean(data.get("success"), true), extractToolName(data));
            case "tool_failed" -> recordToolResult(data, turn, false, extractToolName(data));
            case "handoff_created" -> {
                recordHandoff(turn);
                recordToolResult(data, turn, true, "handoff_create");
            }
            case "handoff_failed" -> {
                recordHandoff(turn);
                recordToolResult(data, turn, false, "handoff_create");
            }
            case "handoff_requested" -> recordHandoff(turn);
            case "llm_called" -> turn.llmCalls++;
            case "llm_failed" -> turn.llmFailures++;
            case "llm_observed" -> recordLlmDuration(data, turn);
            default -> {
                // 其他 Trace 事件暂时不参与 Monitor 聚合，例如 action_planned、final_answer。
            }
        }
    }

    private void recordToolResult(Map<String, Object> data, TurnAccumulator turn, boolean success, String toolName) {
        turn.toolCalls++;
        if (!success) {
            turn.failedToolCalls++;
        }

        Long durationMs = asLong(data.get("durationMs"));
        if (durationMs != null) {
            turn.toolDurationMs += durationMs;
            turn.toolDurationSamples++;
        }

        recordToolStats(toolName, success, durationMs);
    }

    private void recordToolStats(String toolName, boolean success, Long durationMs) {
        String actualToolName = toolName == null || toolName.isBlank() ? "unknown_tool" : toolName;
        ToolAccumulator stats = toolStats.computeIfAbsent(actualToolName, ignored -> new ToolAccumulator());

        stats.callCount++;
        if (success) {
            stats.successfulCallCount++;
        } else {
            stats.failedCallCount++;
        }

        if (durationMs != null) {
            stats.totalDurationMs += durationMs;
            stats.durationSamples++;
        }
    }

    private List<ToolMonitorSnapshot> buildToolStats() {
        List<ToolMonitorSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, ToolAccumulator> entry : toolStats.entrySet()) {
            ToolAccumulator stats = entry.getValue();
            snapshots.add(new ToolMonitorSnapshot(
                    entry.getKey(),
                    stats.callCount,
                    stats.successfulCallCount,
                    stats.failedCallCount,
                    rate(stats.successfulCallCount, stats.callCount),
                    average(stats.totalDurationMs, stats.durationSamples)
            ));
        }
        return snapshots;
    }

    private void recordLlmDuration(Map<String, Object> data, TurnAccumulator turn) {
        Long durationMs = asLong(data.get("durationMs"));
        if (durationMs != null) {
            turn.llmDurationMs += durationMs;
            turn.llmDurationSamples++;
        }
    }

    private void recordHandoff(TurnAccumulator turn) {
        // 一轮 Trace 里通常会同时有 handoff_requested 和 handoff_created。
        // 这里用标记位保证同一轮只统计一次转人工。
        if (!turn.handoffRecorded) {
            turn.handoffCount++;
            turn.handoffRecorded = true;
        }
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractToolName(Map<String, Object> data) {
        Object value = data.get("capability");
        if (value == null) {
            value = data.get("toolName");
        }
        if (value == null) {
            value = data.get("tool");
        }
        return value == null ? "unknown_tool" : value.toString();
    }

    /**
     * 统计当前候选池里某个审核状态的数量。
     *
     * 第一版候选池保存在内存 recentEvalCandidates 中，所以这里直接基于快照列表计算，
     * 可以避免“候选样本已经审核了，但计数器忘记同步更新”的问题。
     */
    private long countEvalCandidates(List<FeedbackEvalCandidate> candidates, FeedbackEvalCandidateStatus status) {
        long count = 0;
        for (FeedbackEvalCandidate candidate : candidates) {
            if (candidate.getStatus() == status) {
                count++;
            }
        }
        return count;
    }

    /**
     * 按问题类型统计候选样本数量。
     *
     * 这里返回 String -> Long，而不是直接返回枚举 Map，
     * 是为了让前端拿到 JSON 后可以直接用枚举名做展示和排序。
     */
    private Map<String, Long> buildEvalCandidateIssueCounts(List<FeedbackEvalCandidate> candidates) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (FeedbackIssueType type : FeedbackIssueType.values()) {
            counts.put(type.name(), 0L);
        }

        for (FeedbackEvalCandidate candidate : candidates) {
            FeedbackIssueType issueType = candidate.getIssueType();
            String key = (issueType == null ? FeedbackIssueType.OTHER : issueType).name();
            counts.put(key, counts.getOrDefault(key, 0L) + 1);
        }
        return counts;
    }

    /**
     * 根据当前 Monitor 指标生成风险告警。
     *
     * 【为什么这里先写固定阈值？】
     * 第一版项目更重要的是把“线上指标 -> 风险判断 -> 处理建议”的闭环跑通。
     * 阈值先写在代码里，测试最稳定、理解成本最低；后续如果接真实流量，再把这些阈值放到配置文件或数据库里。
     */
    private List<MonitorAlert> buildAlerts(double toolSuccessRate,
                                           double llmFailureRate,
                                           double helpfulFeedbackRate,
                                           long pendingEvalCandidateCount,
                                           Map<String, Long> issueCounts) {
        List<MonitorAlert> alerts = new ArrayList<>();

        if (toolCallCount >= 3 && toolSuccessRate < 0.8) {
            alerts.add(new MonitorAlert(
                    MonitorAlertSeverity.WARN,
                    "TOOL_SUCCESS_RATE_LOW",
                    "工具调用成功率偏低",
                    "最近累计工具调用成功率为 " + percentText(toolSuccessRate) + "，可能影响订单查询、退货申请等关键流程。",
                    "优先查看 Tool Stats 中失败率最高的工具，再检查参数校验、模拟数据和 MCP 调用链路。"
            ));
        }

        if (llmCallCount >= 2 && llmFailureRate >= 0.5) {
            MonitorAlertSeverity severity = llmFailureRate >= 0.8
                    ? MonitorAlertSeverity.CRITICAL
                    : MonitorAlertSeverity.WARN;
            alerts.add(new MonitorAlert(
                    severity,
                    "LLM_FAILURE_RATE_HIGH",
                    "LLM 调用失败率偏高",
                    "最近累计 LLM 失败率为 " + percentText(llmFailureRate) + "，Agent 可能频繁走降级回复。",
                    "检查模型配置、API Key、网络状态和降级策略是否符合预期。"
            ));
        }

        if (feedbackCount >= 3 && helpfulFeedbackRate < 0.6) {
            alerts.add(new MonitorAlert(
                    MonitorAlertSeverity.WARN,
                    "HELPFUL_FEEDBACK_RATE_LOW",
                    "用户有用反馈比例偏低",
                    "当前有用反馈比例为 " + percentText(helpfulFeedbackRate) + "，说明回复质量可能没有稳定满足用户预期。",
                    "结合 Recent Feedback 和 Eval Candidates，优先补充缺失信息、政策边界和回复模板。"
            ));
        }

        long unsafePromiseCount = countIssue(issueCounts, FeedbackIssueType.UNSAFE_PROMISE);
        if (unsafePromiseCount > 0) {
            alerts.add(new MonitorAlert(
                    MonitorAlertSeverity.CRITICAL,
                    "UNSAFE_PROMISE_DETECTED",
                    "发现不安全承诺反馈",
                    "候选样本中存在 " + unsafePromiseCount + " 条不安全承诺问题，例如保证退款、保证赔偿等。",
                    "把相关样本优先加入 Eval，强化提示词里的政策边界和禁止承诺规则。"
            ));
        }

        long internalLeakCount = countIssue(issueCounts, FeedbackIssueType.TOOL_OR_INTERNAL_LEAK);
        if (internalLeakCount > 0) {
            alerts.add(new MonitorAlert(
                    MonitorAlertSeverity.CRITICAL,
                    "INTERNAL_DETAIL_LEAK_DETECTED",
                    "发现内部实现泄露反馈",
                    "候选样本中存在 " + internalLeakCount + " 条内部工具或 Trace 泄露问题。",
                    "检查最终回复过滤逻辑，避免把 tool、trace、debug 等内部词泄露给用户。"
            ));
        }

        if (pendingEvalCandidateCount >= 5) {
            alerts.add(new MonitorAlert(
                    MonitorAlertSeverity.WARN,
                    "EVAL_CANDIDATE_BACKLOG",
                    "待审核评测候选样本较多",
                    "当前仍有 " + pendingEvalCandidateCount + " 条负反馈候选样本等待人工审核。",
                    "建议定期审核候选样本，把高价值问题沉淀到正式 Eval case，避免反馈闭环断掉。"
            ));
        }

        return alerts;
    }

    private long countIssue(Map<String, Long> issueCounts, FeedbackIssueType issueType) {
        if (issueCounts == null || issueType == null) {
            return 0;
        }
        return issueCounts.getOrDefault(issueType.name(), 0L);
    }

    /**
     * 从本地存储恢复 Eval 候选样本池。
     *
     * 这里只恢复“候选样本池”相关数据，不恢复工具调用数、LLM 调用数等在线指标。
     * 原因是那些指标更像运行期监控数据，项目重启后重新统计即可；候选样本则是需要人工审核的质量资产，不能丢。
     */
    private void restoreEvalCandidateState(FeedbackEvalCandidateStore.State state) {
        if (state == null) {
            return;
        }

        recentEvalCandidates.clear();
        for (FeedbackEvalCandidate candidate : state.getCandidates()) {
            recentEvalCandidates.addLast(candidate);
            if (recentEvalCandidates.size() >= RECENT_EVAL_CANDIDATE_LIMIT) {
                break;
            }
        }

        feedbackSequence = Math.max(state.getFeedbackSequence(), maxFeedbackSequence(state.getCandidates()));
        evalCandidateSequence = Math.max(state.getEvalCandidateSequence(), maxEvalCandidateSequence(state.getCandidates()));
        evalCandidateCount = Math.max(state.getEvalCandidateCount(), recentEvalCandidates.size());
        if (!recentEvalCandidates.isEmpty()) {
            lastUpdatedAt = System.currentTimeMillis();
        }
    }

    private void persistEvalCandidateState() {
        candidateStore.save(new FeedbackEvalCandidateStore.State(
                new ArrayList<>(recentEvalCandidates),
                feedbackSequence,
                evalCandidateSequence,
                evalCandidateCount
        ));
    }

    private long maxFeedbackSequence(List<FeedbackEvalCandidate> candidates) {
        long max = 0;
        for (FeedbackEvalCandidate candidate : candidates) {
            max = Math.max(max, sequenceFromId(candidate.getFeedbackId(), "FDB-"));
        }
        return max;
    }

    private long maxEvalCandidateSequence(List<FeedbackEvalCandidate> candidates) {
        long max = 0;
        for (FeedbackEvalCandidate candidate : candidates) {
            max = Math.max(max, sequenceFromId(candidate.getCandidateId(), "EVAL-CAND-"));
        }
        return max;
    }

    private long sequenceFromId(String id, String prefix) {
        if (id == null || !id.startsWith(prefix)) {
            return 0;
        }
        try {
            return Long.parseLong(id.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private FeedbackIssueType classifyFeedbackIssue(FeedbackRecord feedback) {
        String reason = feedback.getReason() == null ? "" : feedback.getReason();
        String assistantReply = feedback.getAssistantReply() == null ? "" : feedback.getAssistantReply();
        String text = reason + " " + assistantReply;

        // 内部工具词泄露和危险承诺优先级最高，因为这两类问题最需要尽快拦截。
        if (containsAny(text, "Tool", "tool_", "内部工具", "工具调用", "debug", "trace")) {
            return FeedbackIssueType.TOOL_OR_INTERNAL_LEAK;
        }
        if (containsAny(text, "一定可以退款", "保证退款", "无条件退款", "一定赔偿", "保证赔偿", "一定可以赔偿")) {
            return FeedbackIssueType.UNSAFE_PROMISE;
        }
        if (containsAny(reason, "没有说明", "没说明", "没说", "没提", "缺少", "不完整", "关键信息", "时效", "订单号", "物流单号")) {
            return FeedbackIssueType.MISSING_KEY_INFO;
        }
        if (containsAny(reason, "政策", "规则", "退货", "退款", "发票", "保修", "配送", "售后")) {
            return FeedbackIssueType.POLICY_UNCLEAR;
        }
        if (containsAny(reason, "语气", "态度", "不礼貌", "敷衍", "不耐烦", "体验不好")) {
            return FeedbackIssueType.TONE_OR_EXPERIENCE;
        }
        return FeedbackIssueType.OTHER;
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private long average(long total, long count) {
        if (count <= 0) {
            return 0;
        }
        return total / count;
    }

    private double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        double value = numerator * 1.0 / denominator;
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private String percentText(double value) {
        return Math.round(value * 10_000.0) / 100.0 + "%";
    }

    private String nextFeedbackId() {
        feedbackSequence++;
        return "FDB-" + String.format("%06d", feedbackSequence);
    }

    private String nextEvalCandidateId() {
        evalCandidateSequence++;
        return "EVAL-CAND-" + String.format("%06d", evalCandidateSequence);
    }

    private void createEvalCandidate(FeedbackRecord feedback) {
        FeedbackEvalCandidate candidate = new FeedbackEvalCandidate(
                nextEvalCandidateId(),
                feedback.getFeedbackId(),
                feedback.getConversationId(),
                feedback.getUserId(),
                feedback.getRating(),
                feedback.getReason(),
                feedback.getAssistantReply(),
                "response_quality_candidate",
                classifyFeedbackIssue(feedback),
                feedback.getTimestamp()
        );

        evalCandidateCount++;
        recentEvalCandidates.addFirst(candidate);
        while (recentEvalCandidates.size() > RECENT_EVAL_CANDIDATE_LIMIT) {
            recentEvalCandidates.removeLast();
        }
        persistEvalCandidateState();
    }

    private FeedbackEvalCaseDraft toEvalCaseDraft(FeedbackEvalCandidate candidate, long generatedAt) {
        return new FeedbackEvalCaseDraft(
                "feedback_" + candidate.getCandidateId().toLowerCase().replace("-", "_"),
                candidate.getCandidateId(),
                candidate.getFeedbackId(),
                candidate.getConversationId(),
                candidate.getUserId(),
                candidate.getSuggestedEvalType(),
                candidate.getIssueType(),
                candidate.getAssistantReply(),
                List.of(),
                DEFAULT_FORBIDDEN_KEYWORDS,
                false,
                candidate.getReason(),
                candidate.getReviewNote(),
                "请人工补充 requiredKeywords，并确认 candidateReply 是否作为负例加入 response_quality_eval_cases.json。",
                generatedAt
        );
    }

    private String trimToLimit(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, limit);
    }

    /**
     * 单轮临时统计对象。
     *
     * 它只在 recordTurn 内部使用，不需要暴露给 Controller。
     */
    private static class TurnAccumulator {
        private long toolCalls;
        private long failedToolCalls;
        private long llmCalls;
        private long llmFailures;
        private long handoffCount;
        private long toolDurationMs;
        private long toolDurationSamples;
        private long llmDurationMs;
        private long llmDurationSamples;
        private boolean handoffRecorded;
    }

    /**
     * 单个工具的累计统计。
     *
     * 它只在 MonitorService 内部使用，对外仍然返回 ToolMonitorSnapshot。
     */
    private static class ToolAccumulator {
        private long callCount;
        private long successfulCallCount;
        private long failedCallCount;
        private long totalDurationMs;
        private long durationSamples;
    }
}
