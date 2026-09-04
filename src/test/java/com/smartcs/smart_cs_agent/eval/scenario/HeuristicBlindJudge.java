package com.smartcs.smart_cs_agent.eval.scenario;

import java.util.List;

/**
 * 确定性启发式盲评：CI 与本地默认的 Judge 实现。
 *
 * <p>诚实声明：它不读语义，只看“该端适用的确定性断言是否全部通过”，
 * taskCompleted 等价于 run.passed()，score 由违规数推导。它的作用是让
 * 盲评管道（匿名标签、多数表决、报告落盘）在无网络的 CI 里可以完整回归，
 * 而不是产出语义分数——语义分数只来自 {@link LlmBlindJudge}（M4 真实跑批）。</p>
 *
 * <p>尽管不看语义，它同样只接收匿名标签，保证两套 Judge 的输入口径完全一致，
 * 切换实现不需要改场景与调用方。</p>
 */
class HeuristicBlindJudge implements BlindScenarioJudge {

    static final String JUDGE_NAME = "heuristic";

    @Override
    public ScenarioJudgement judge(Scenario scenario, ScenarioRunResult run, String agentLabel) {
        List<String> violations = run.allViolations();
        boolean completed = run.passed();
        int score = completed ? 5 : scoreOf(violations.size());
        String rationale = completed
                ? "确定性断言全部通过（" + run.records().size() + " 轮）"
                : "断言违规 " + violations.size() + " 条，如：" + violations.get(0);
        return new ScenarioJudgement(agentLabel, completed, score, rationale, JUDGE_NAME);
    }

    private int scoreOf(int violationCount) {
        if (violationCount <= 2) {
            return 4;
        }
        if (violationCount <= 5) {
            return 3;
        }
        if (violationCount <= 9) {
            return 2;
        }
        return 1;
    }
}
