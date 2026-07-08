package com.smartcs.smart_cs_agent.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Eval 候选样本的本地文件存储。
 *
 * 【为什么先用 JSON 文件？】
 * 当前项目是简历和面试展示项目，第一目标是把“无用反馈 -> 候选样本 -> 审核 -> 导出 Eval 草稿”
 * 这条质量闭环跑完整。如果一开始就上 MySQL，会引入表结构、迁移脚本和部署依赖，学习成本会变高。
 *
 * 所以这里先用一个本地 JSON 文件保存候选样本池：
 * - 项目重启后，待审核样本不会丢；
 * - 测试可以用临时文件验证恢复逻辑；
 * - 后续要换数据库时，只需要替换这个 Store，MonitorService 的主流程不用大改。
 */
@Component
public class FeedbackEvalCandidateStore {

    private final ObjectMapper objectMapper;
    private final Path storagePath;
    private final boolean enabled;

    @Autowired
    public FeedbackEvalCandidateStore(ObjectMapper objectMapper,
                                      @Value("${smartcs.monitor.eval-candidates-file:data/feedback-eval-candidates.json}")
                                      String storageFile) {
        this(objectMapper, Path.of(storageFile), true);
    }

    FeedbackEvalCandidateStore(Path storagePath, ObjectMapper objectMapper) {
        this(objectMapper, storagePath, true);
    }

    private FeedbackEvalCandidateStore(ObjectMapper objectMapper, Path storagePath, boolean enabled) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.storagePath = storagePath;
        this.enabled = enabled;
    }

    static FeedbackEvalCandidateStore disabled() {
        return new FeedbackEvalCandidateStore(new ObjectMapper(), null, false);
    }

    /**
     * 从本地 JSON 文件恢复候选样本池状态。
     *
     * 如果文件还不存在，说明当前是第一次启动，直接返回空状态即可。
     */
    synchronized State load() {
        if (!enabled || storagePath == null || !Files.exists(storagePath)) {
            return State.empty();
        }

        try {
            PersistedState persistedState = objectMapper.readValue(storagePath.toFile(), PersistedState.class);
            return persistedState == null ? State.empty() : persistedState.toState();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load feedback eval candidates from " + storagePath, e);
        }
    }

    /**
     * 保存候选样本池状态。
     *
     * 当前数据量很小，直接覆盖整个 JSON 文件最简单、也最容易理解。
     * 如果后续接入真实流量，可以再改成数据库 upsert 或追加日志。
     */
    synchronized void save(State state) {
        if (!enabled || storagePath == null) {
            return;
        }

        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            PersistedState persistedState = PersistedState.fromState(state == null ? State.empty() : state);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storagePath.toFile(), persistedState);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save feedback eval candidates to " + storagePath, e);
        }
    }

    /**
     * MonitorService 关心的运行时状态。
     *
     * 除了候选样本列表，还要保存 sequence 和累计数量。
     * 否则项目重启后新生成的 ID 可能又从 1 开始，导致前端和日志里出现重复 ID。
     */
    static class State {

        private final List<FeedbackEvalCandidate> candidates;
        private final long feedbackSequence;
        private final long evalCandidateSequence;
        private final long evalCandidateCount;

        State(List<FeedbackEvalCandidate> candidates,
              long feedbackSequence,
              long evalCandidateSequence,
              long evalCandidateCount) {
            this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
            this.feedbackSequence = feedbackSequence;
            this.evalCandidateSequence = evalCandidateSequence;
            this.evalCandidateCount = evalCandidateCount;
        }

        static State empty() {
            return new State(List.of(), 0, 0, 0);
        }

        List<FeedbackEvalCandidate> getCandidates() {
            return candidates;
        }

        long getFeedbackSequence() {
            return feedbackSequence;
        }

        long getEvalCandidateSequence() {
            return evalCandidateSequence;
        }

        long getEvalCandidateCount() {
            return evalCandidateCount;
        }
    }

    /**
     * 真正写入 JSON 的结构。
     *
     * 这里不用直接把 FeedbackEvalCandidate 写入文件，是为了让持久化格式和领域对象解耦。
     * 以后领域对象加字段时，只要在 Snapshot 里明确处理，就不容易把历史文件读坏。
     */
    public static class PersistedState {

        public long feedbackSequence;
        public long evalCandidateSequence;
        public long evalCandidateCount;
        public List<CandidateSnapshot> candidates = new ArrayList<>();

        static PersistedState fromState(State state) {
            PersistedState persistedState = new PersistedState();
            persistedState.feedbackSequence = state.getFeedbackSequence();
            persistedState.evalCandidateSequence = state.getEvalCandidateSequence();
            persistedState.evalCandidateCount = state.getEvalCandidateCount();
            for (FeedbackEvalCandidate candidate : state.getCandidates()) {
                persistedState.candidates.add(CandidateSnapshot.fromCandidate(candidate));
            }
            return persistedState;
        }

        State toState() {
            List<FeedbackEvalCandidate> restoredCandidates = new ArrayList<>();
            if (candidates != null) {
                for (CandidateSnapshot snapshot : candidates) {
                    if (snapshot != null) {
                        restoredCandidates.add(snapshot.toCandidate());
                    }
                }
            }
            return new State(restoredCandidates, feedbackSequence, evalCandidateSequence, evalCandidateCount);
        }
    }

    public static class CandidateSnapshot {

        public String candidateId;
        public String feedbackId;
        public String conversationId;
        public String userId;
        public FeedbackRating rating;
        public String reason;
        public String assistantReply;
        public String suggestedEvalType;
        public FeedbackIssueType issueType;
        public FeedbackEvalCandidateStatus status;
        public String reviewNote;
        public long reviewedAt;
        public long timestamp;

        static CandidateSnapshot fromCandidate(FeedbackEvalCandidate candidate) {
            CandidateSnapshot snapshot = new CandidateSnapshot();
            snapshot.candidateId = candidate.getCandidateId();
            snapshot.feedbackId = candidate.getFeedbackId();
            snapshot.conversationId = candidate.getConversationId();
            snapshot.userId = candidate.getUserId();
            snapshot.rating = candidate.getRating();
            snapshot.reason = candidate.getReason();
            snapshot.assistantReply = candidate.getAssistantReply();
            snapshot.suggestedEvalType = candidate.getSuggestedEvalType();
            snapshot.issueType = candidate.getIssueType();
            snapshot.status = candidate.getStatus();
            snapshot.reviewNote = candidate.getReviewNote();
            snapshot.reviewedAt = candidate.getReviewedAt();
            snapshot.timestamp = candidate.getTimestamp();
            return snapshot;
        }

        FeedbackEvalCandidate toCandidate() {
            return new FeedbackEvalCandidate(
                    blankToDefault(candidateId, "EVAL-CAND-000000"),
                    blankToDefault(feedbackId, "FDB-000000"),
                    blankToDefault(conversationId, "unknown-conversation"),
                    blankToDefault(userId, "anonymous"),
                    rating == null ? FeedbackRating.UNHELPFUL : rating,
                    reason == null ? "" : reason,
                    assistantReply == null ? "" : assistantReply,
                    blankToDefault(suggestedEvalType, "response_quality_candidate"),
                    issueType == null ? FeedbackIssueType.OTHER : issueType,
                    status == null ? FeedbackEvalCandidateStatus.PENDING_REVIEW : status,
                    reviewNote == null ? "" : reviewNote,
                    reviewedAt,
                    timestamp
            );
        }

        private String blankToDefault(String value, String defaultValue) {
            return value == null || value.isBlank() ? defaultValue : value;
        }
    }
}
