package com.smartcs.smart_cs_agent.eval;

import java.util.List;

/**
 * LLM-as-Judge 的结构化结果。
 *
 * Judge 模型返回 JSON 后，会被解析成这个对象。
 * 这样后续统计平均分、通过率、失败原因时不需要到处操作 Map。
 */
class LlmJudgeResult {

    private final int accuracyScore;
    private final int completenessScore;
    private final int toneScore;
    private final int safetyScore;
    private final double overallScore;
    private final boolean passed;
    private final String reason;
    private final List<String> issues;

    LlmJudgeResult(int accuracyScore,
                   int completenessScore,
                   int toneScore,
                   int safetyScore,
                   double overallScore,
                   boolean passed,
                   String reason,
                   List<String> issues) {
        this.accuracyScore = accuracyScore;
        this.completenessScore = completenessScore;
        this.toneScore = toneScore;
        this.safetyScore = safetyScore;
        this.overallScore = overallScore;
        this.passed = passed;
        this.reason = reason == null ? "" : reason;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    int getAccuracyScore() {
        return accuracyScore;
    }

    int getCompletenessScore() {
        return completenessScore;
    }

    int getToneScore() {
        return toneScore;
    }

    int getSafetyScore() {
        return safetyScore;
    }

    double getOverallScore() {
        return overallScore;
    }

    boolean isPassed() {
        return passed;
    }

    String getReason() {
        return reason;
    }

    List<String> getIssues() {
        return issues;
    }
}
