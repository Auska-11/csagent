package com.smartcs.smart_cs_agent.eval.scenario;

import com.smartcs.smart_cs_agent.eval.EvalReportWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景化对照实验的 Workflow 侧回归入口：场景 → HTTP → Trace → 确定性断言 的端到端链路。
 *
 * <p>这个测试只保证“链路与断言机制正确”，不产出对比数字；数字来自 M3/M4 的
 * 真实模型跑批。默认上下文把 ChatLanguageModel 换成 {@link ScriptedFaultChatModel}，
 * 因此本地与 CI 都不依赖网络（76 号计划 CI 策略）。</p>
 *
 * <p>加载 M3 的 60 条全量场景集（六类六文件），只跑 Workflow 端点的确定性断言；
 * 双端对照、盲评与多数表决见 {@link ScenarioComparisonEvaluationTest}。</p>
 *
 * <p>call-timeout-ms 收紧到 600ms，是为了让 timeout 故障注入的等待时间可控：
 * Guard 会在 600ms 后判定 LLM_CALL_DEADLINE_EXCEEDED 并走模板降级，
 * 遗留的守护线程 sleep 不阻塞测试。</p>
 */
@SpringBootTest(properties = "smartcs.llm.execution.call-timeout-ms=600")
@AutoConfigureMockMvc
class ScenarioRunnerEvaluationTest {

    private static final List<String> FULL_SCENARIO_FILES = List.of(
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
    void fullScenariosShouldAllPassOnWorkflowEndpoint() throws Exception {
        ScenarioRunner runner = new ScenarioRunner(mockMvc);
        List<Scenario> scenarios = runner.loadScenarios(FULL_SCENARIO_FILES);
        List<ScenarioRunResult> results = runner.runAll(scenarios);

        writeReport(results);

        List<String> failures = new ArrayList<>();
        for (ScenarioRunResult result : results) {
            if (!result.passed()) {
                failures.add("场景执行失败：" + result.scenario().getId()
                        + " (repeat " + result.repeatOf() + ")\n  "
                        + String.join("\n  ", result.allViolations()));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private void writeReport(List<ScenarioRunResult> results) throws Exception {
        int total = results.size();
        long passed = results.stream().filter(ScenarioRunResult::passed).count();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRuns", total);
        summary.put("passedRuns", passed);
        summary.put("passRate", EvalReportWriter.rate((int) passed, total));
        summary.put("llmMode", "scripted-fake");
        summary.put("endpoint", ScenarioRunner.WORKFLOW_ENDPOINT);

        Map<String, int[]> byCategory = new LinkedHashMap<>();
        for (ScenarioRunResult result : results) {
            String category = String.valueOf(result.scenario().category);
            byCategory.computeIfAbsent(category, key -> new int[]{0, 0});
            int[] counts = byCategory.get(category);
            counts[1]++;
            if (result.passed()) {
                counts[0]++;
            }
        }
        Map<String, Object> categoryRates = new LinkedHashMap<>();
        byCategory.forEach((category, counts) ->
                categoryRates.put(category, counts[0] + "/" + counts[1]));
        summary.put("byCategory", categoryRates);

        List<Map<String, Object>> rows = results.stream()
                .map(ScenarioRunResult::toReportRow)
                .toList();
        EvalReportWriter.write("scenario-runner", summary, rows);
    }
}
