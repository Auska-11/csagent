package com.smartcs.smart_cs_agent.eval.scenario;

/**
 * 盲评 Judge 的判定结果。
 *
 * @param agentLabel    被评方的匿名标签（Agent A / Agent B），落盘报告只允许出现它
 * @param taskCompleted 按场景意图，任务是否完成
 * @param score         1-5 整体分；Heuristic 实现由断言违规数推导，LLM 实现由模型给出
 * @param rationale     一句话依据；判定必须可追溯
 * @param judgeName     评判者标识（heuristic / llm），报告中必须区分数字来源
 */
record ScenarioJudgement(
        String agentLabel,
        boolean taskCompleted,
        int score,
        String rationale,
        String judgeName) {
}
