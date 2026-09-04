package com.smartcs.smart_cs_agent.eval.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.eval.EvalReportWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 双跑对照实验入口：同一份 60 条场景集，对 Workflow 端点与基线端点各跑一遍
 * （公平原则第 5 条：相同消息序列、相同身份、相同种子数据；第 7 条：两侧各按
 * 自己适用的断言判定，不要求行为同构）。
 *
 * <p>判定分两层：</p>
 * <ul>
 *   <li>确定性断言（{@link ScenarioAssertionEngine}）：安全、副作用、能力通道、降级存活，
 *       CI 里必须全绿；</li>
 *   <li>盲评（{@link HeuristicBlindJudge}，CI 确定性占位）：只见匿名标签 Agent A/B，
 *       M4 换 {@link LlmBlindJudge} 出语义分。对抗/幻觉类场景 repeat=3，按多数表决聚合。</li>
 * </ul>
 *
 * <p>本测试不产出对比结论数字（Fake LLM 下两端回复高度模板化）；对比报告与结论
 * 由 M4 真实模型跑批生成，本测试落盘的 scenario-comparison 报告用于验证管道与结构。</p>
 */
@SpringBootTest(properties = "smartcs.llm.execution.call-timeout-ms=600")
@AutoConfigureMockMvc
class ScenarioComparisonEvaluationTest {

    /** 六个场景文件按固定顺序拼接成 60 条目录；包可见，M4 真实跑批测试共用同一份目录源。 */
    static final List<String> FULL_SCENARIO_FILES = List.of(
            "/eval/scenario/full/normal.json",
            "/eval/scenario/full/hard.json",
            "/eval/scenario/full/adversarial.json",
            "/eval/scenario/full/hallucination.json",
            "/eval/scenario/full/fault.json",
            "/eval/scenario/full/chitchat.json");

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class FakeLlmConfiguration {

        /** 覆盖 langchain4j 自动装配的 OpenAI 模型，保证评测不触网。 */
        @Bean
        @Primary
        ScriptedFaultChatModel scriptedFaultChatModel() {
            return new ScriptedFaultChatModel();
        }
    }

    @Test
    void fullScenariosShouldPassOnBothSidesWithBlindJudgeAndMajorityVote() throws Exception {
        ScenarioRunner workflowRunner = new ScenarioRunner(mockMvc, ScenarioRunner.WORKFLOW_ENDPOINT);
        ScenarioRunner baselineRunner = new ScenarioRunner(mockMvc, ScenarioRunner.BASELINE_ENDPOINT);
        List<Scenario> scenarios = workflowRunner.loadScenarios(FULL_SCENARIO_FILES);

        assertCatalogShape(scenarios);

        List<ScenarioRunResult> workflowResults = workflowRunner.runAll(scenarios);
        List<ScenarioRunResult> baselineResults = baselineRunner.runAll(scenarios);

        BlindScenarioJudge judge = new HeuristicBlindJudge();
        ComparisonReport report = ComparisonReport.build(scenarios, workflowResults, baselineResults, judge);
        report.writeTo("scenario-comparison");

        // 确定性断言是 CI 红线：两侧全过才允许构建通过。
        assertAllPassed(EndpointSide.WORKFLOW, workflowResults);
        assertAllPassed(EndpointSide.BASELINE, baselineResults);

        // 多数表决在 Fake 下必须全数通过（确定性执行不允许抖动）。
        for (Map.Entry<String, MajorityVerdict> entry : report.verdicts.entrySet()) {
            assertTrue(entry.getValue().majorityPassed(),
                    entry.getKey() + " 多数表决未通过：" + entry.getValue());
        }
    }

    /** 盲评 prompt 必须匿名：不携带端点名、实现方式词——这是公平原则第 6 条的结构保证。 */
    @Test
    void blindJudgePromptMustNotLeakAgentIdentity() {
        Scenario scenario = new Scenario();
        scenario.id = "blindness-probe";
        scenario.description = "盲性探针：验证 Judge prompt 不泄漏被测方身份";
        scenario.persona = new ScenarioPersona();
        scenario.persona.userId = "USER-001";
        TurnExecutionRecord record = new TurnExecutionRecord(
                1, "帮我查一下订单 ORD-001 的物流信息", 200,
                "订单 ORD-001 信息：- 商品：蓝牙耳机 Pro Max - 物流单号：SF1234567890",
                List.of(), "SUCCESS", "", List.of("order.lookup"), false, List.of());
        ScenarioRunResult run = new ScenarioRunResult(
                scenario, 1, List.of(record), List.of(), true);

        for (String label : List.of("Agent A", "Agent B")) {
            String prompt = LlmBlindJudge.buildPrompt(scenario, run, label);
            assertTrue(LlmBlindJudge.isBlindPrompt(prompt),
                    "盲评 prompt 泄漏了身份词：" + LlmBlindJudge.FORBIDDEN_IDENTITY_WORDS);
            assertTrue(prompt.contains(label), "prompt 应携带匿名标签 " + label);
        }
    }

    /**
     * 真实盲评的结构前提（公平原则第 6 条）：全部 60 条场景都必须携带身份中立的
     * judgeIntent，且在两个匿名标签下拼出的 Judge prompt 均不含身份词。
     * description 里记录着 M3 校准发现（含端点行为差异），不允许进入盲评 prompt；
     * 这条测试离线运行（不依赖 Spring 上下文与网络），保证 M4 真实跑批前口径就被守住。
     */
    @Test
    void allScenariosMustCarryNeutralJudgeIntentForRealBlindEvaluation() throws IOException {
        List<Scenario> scenarios = loadScenarioCatalog();
        assertEquals(60, scenarios.size(), "盲性校验应覆盖全部 60 条场景");
        for (Scenario scenario : scenarios) {
            assertTrue(scenario.judgeIntent != null && !scenario.judgeIntent.isBlank(),
                    scenario.getId() + " 缺少身份中立的 judgeIntent，真实盲评会回退到"
                            + "携带校准记录的 description 而破坏盲性");
            ScenarioRunResult emptyRun = new ScenarioRunResult(scenario, 1, List.of(), List.of(), true);
            for (String label : List.of("Agent A", "Agent B")) {
                String prompt = LlmBlindJudge.buildPrompt(scenario, emptyRun, label);
                assertTrue(LlmBlindJudge.isBlindPrompt(prompt),
                        scenario.getId() + " 的 judgeIntent 含身份词，" + label + " 视角 prompt 泄漏");
            }
        }
    }

    /** 离线加载 60 条场景目录（不开 Spring 上下文），供盲性结构校验使用。 */
    private List<Scenario> loadScenarioCatalog() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Scenario> scenarios = new ArrayList<>();
        for (String path : FULL_SCENARIO_FILES) {
            try (InputStream inputStream = getClass().getResourceAsStream(path)) {
                if (inputStream == null) {
                    throw new IllegalStateException("场景文件不存在：" + path);
                }
                scenarios.addAll(mapper.readValue(inputStream,
                        mapper.getTypeFactory().constructCollectionType(List.class, Scenario.class)));
            }
        }
        return scenarios;
    }

    /** 场景库形态约束：总数 60、六类配比固定——防止后续增删场景时悄悄改变实验口径。 */
    private void assertCatalogShape(List<Scenario> scenarios) {
        assertEquals(60, scenarios.size(), "场景总数应为 60");
        Map<String, Integer> expected = Map.of(
                "NORMAL", 10, "HARD", 10, "ADVERSARIAL", 12,
                "HALLUCINATION", 8, "FAULT", 10, "CHITCHAT", 10);
        Map<String, Integer> actual = new TreeMap<>();
        scenarios.forEach(scenario -> actual.merge(scenario.category().name(), 1, Integer::sum));
        assertEquals(expected, actual, "六类场景配比不符合 3.3 节设计");
    }

    private void assertAllPassed(EndpointSide side, List<ScenarioRunResult> results) {
        List<String> failures = new ArrayList<>();
        for (ScenarioRunResult result : results) {
            if (!result.passed()) {
                failures.add(result.scenario().getId() + " (repeat " + result.repeatOf() + ")\n  "
                        + String.join("\n  ", result.allViolations()));
            }
        }
        assertTrue(failures.isEmpty(),
                side + " 端存在失败场景（" + failures.size() + " 个 run）：\n"
                        + String.join("\n", failures));
    }

    /** 单场景在一侧的多数表决结果。 */
    private record MajorityVerdict(
            EndpointSide side,
            String agentLabel,
            int runs,
            int passedRuns,
            boolean majorityPassed,
            int judgeCompletedRuns,
            boolean majorityTaskCompleted,
            double avgJudgeScore,
            List<String> violations) {

        @Override
        public String toString() {
            return side + "(" + agentLabel + ") runs=" + runs + " passed=" + passedRuns
                    + " judgeCompleted=" + judgeCompletedRuns + " avgScore=" + avgJudgeScore
                    + (violations.isEmpty() ? "" : " violations=" + violations);
        }
    }

    /** 匿名双跑报告：summary 按 Agent A/B 聚合；披露映射放在 disclosure 字段（Judge 看不到报告）。 */
    private static final class ComparisonReport {

        private final Map<String, MajorityVerdict> verdicts = new LinkedHashMap<>();
        private final Map<String, Object> summary = new LinkedHashMap<>();
        private final List<Map<String, Object>> rows = new ArrayList<>();

        static ComparisonReport build(List<Scenario> scenarios,
                                      List<ScenarioRunResult> workflowResults,
                                      List<ScenarioRunResult> baselineResults,
                                      BlindScenarioJudge judge) {
            ComparisonReport report = new ComparisonReport();
            report.collectPerSide(EndpointSide.WORKFLOW, scenarios, workflowResults, judge);
            report.collectPerSide(EndpointSide.BASELINE, scenarios, baselineResults, judge);
            report.buildSummary(scenarios, workflowResults, baselineResults);
            return report;
        }

        private void collectPerSide(EndpointSide side,
                                    List<Scenario> scenarios,
                                    List<ScenarioRunResult> results,
                                    BlindScenarioJudge judge) {
            for (Scenario scenario : scenarios) {
                List<ScenarioRunResult> runs = results.stream()
                        .filter(result -> result.scenario().getId().equals(scenario.getId()))
                        .sorted(Comparator.comparingInt(ScenarioRunResult::repeatOf))
                        .toList();

                int passed = 0;
                int judgeCompleted = 0;
                double scoreSum = 0;
                List<String> violations = new ArrayList<>();
                List<Map<String, Object>> runRows = new ArrayList<>();
                for (ScenarioRunResult run : runs) {
                    if (run.passed()) {
                        passed++;
                    }
                    ScenarioJudgement judgement =
                            judge.judge(scenario, run, side.blindLabel());
                    if (judgement.taskCompleted()) {
                        judgeCompleted++;
                    }
                    scoreSum += judgement.score();
                    run.allViolations().forEach(violation -> violations.add(
                            "r" + run.repeatOf() + ": " + violation));
                    Map<String, Object> runRow = new LinkedHashMap<>();
                    runRow.put("repeat", run.repeatOf());
                    runRow.put("passed", run.passed());
                    runRow.put("judgeTaskCompleted", judgement.taskCompleted());
                    runRow.put("judgeScore", judgement.score());
                    runRow.put("turns", run.toReportRow().get("turns"));
                    runRows.add(runRow);
                }

                MajorityVerdict verdict = new MajorityVerdict(
                        side, side.blindLabel(), runs.size(), passed,
                        passed * 2 > runs.size(),
                        judgeCompleted, judgeCompleted * 2 > runs.size(),
                        runs.isEmpty() ? 0 : scoreSum / runs.size(),
                        violations);
                verdicts.put(side.name() + ":" + scenario.getId(), verdict);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", scenario.getId());
                row.put("category", scenario.category);
                row.put("agent", side.blindLabel());
                row.put("runs", runs.size());
                row.put("passedRuns", passed);
                row.put("majorityPassed", verdict.majorityPassed());
                row.put("judgeCompletedRuns", judgeCompleted);
                row.put("majorityTaskCompleted", verdict.majorityTaskCompleted());
                row.put("avgJudgeScore", verdict.avgJudgeScore());
                row.put("violations", violations);
                row.put("runDetails", runRows);
                rows.add(row);
            }
        }

        private void buildSummary(List<Scenario> scenarios,
                                  List<ScenarioRunResult> workflowResults,
                                  List<ScenarioRunResult> baselineResults) {
            summary.put("totalScenarios", scenarios.size());
            summary.put("llmMode", "scripted-fake");
            summary.put("judge", HeuristicBlindJudge.JUDGE_NAME);
            summary.put("majorityVoteNote", "ADVERSARIAL/HALLUCINATION 场景 repeat=3，passes > runs/2 判过");
            summary.put("agents", Map.of(
                    "Agent A", agentSummary(EndpointSide.WORKFLOW.blindLabel(), workflowResults),
                    "Agent B", agentSummary(EndpointSide.BASELINE.blindLabel(), baselineResults)));
            // 盲评针对 Judge；报告读者需要映射才能解读，评测后再披露不破坏盲性。
            summary.put("disclosure", Map.of("Agent A", "workflow", "Agent B", "baseline"));
        }

        private Map<String, Object> agentSummary(String label, List<ScenarioRunResult> results) {
            long passed = results.stream().filter(ScenarioRunResult::passed).count();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", label);
            item.put("totalRuns", results.size());
            item.put("passedRuns", passed);
            item.put("passRate", EvalReportWriter.rate((int) passed, results.size()));
            return item;
        }

        void writeTo(String reportName) throws java.io.IOException {
            List<Map<String, Object>> sortedRows = new ArrayList<>(rows);
            sortedRows.sort(Comparator
                    .comparing((Map<String, Object> row) -> String.valueOf(row.get("id")))
                    .thenComparing(row -> String.valueOf(row.get("agent"))));
            EvalReportWriter.write(reportName, summary, sortedRows);
        }
    }
}
