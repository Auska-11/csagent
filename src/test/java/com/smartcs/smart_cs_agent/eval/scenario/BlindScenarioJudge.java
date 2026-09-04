package com.smartcs.smart_cs_agent.eval.scenario;

/**
 * 盲评 Judge：对单个场景的一次执行给出 taskCompleted / score / rationale。
 *
 * <p>公平原则第 6 条（76 号计划 3.2 节）：Judge 只能看到匿名标签
 * {@code 客服 Agent（Agent A）}，看不到被测方是 Workflow 还是基线，也看不到
 * 端点 URL。调用方负责传入匿名标签并保证 prompt 不泄漏端点名。</p>
 *
 * <p>两套实现（对应 3.4 节 CI 策略）：</p>
 * <ul>
 *   <li>{@link HeuristicBlindJudge}：确定性占位，CI 里保证结构可跑、不出语义分；</li>
 *   <li>{@link LlmBlindJudge}：真实模型盲评，M4 跑批时通过系统属性启用。</li>
 * </ul>
 */
interface BlindScenarioJudge {

    /**
     * 评判一次场景执行。
     *
     * @param scenario   场景定义（意图说明供 Judge 理解任务）
     * @param run        该次执行的轮次记录与断言结果
     * @param agentLabel 匿名标签，如 "Agent A"
     */
    ScenarioJudgement judge(Scenario scenario, ScenarioRunResult run, String agentLabel);
}
