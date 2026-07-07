package com.smartcs.smart_cs_agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM-as-Judge 评测第一版。
 *
 * 【当前为什么用 Fake Judge？】
 * 真实 Judge 模型需要网络、API Key 和稳定模型输出，不适合作为默认单元测试依赖。
 * 所以第一版先验证：
 * - Judge Prompt 能包含必要上下文和四个评分维度；
 * - Judge JSON 能被稳定解析；
 * - 评测结果能输出成 JSON 报告；
 * - 通过率、平均分等指标能进入 Eval 看板。
 *
 * 后续接真实模型时，可以把 FakeJudgeModel 替换成真实 ChatLanguageModel，
 * 但评测报告结构不用大改。
 */
class LlmAsJudgeEvaluationTest {

    @Test
    void shouldPassLlmAsJudgeEvaluation() throws IOException {
        List<LlmJudgeEvalCase> cases = loadCases();
        LlmJudgeReport report = new LlmJudgeReport();
        List<String> failures = new ArrayList<>();

        for (LlmJudgeEvalCase evalCase : cases) {
            LlmJudgeEvaluator evaluator = new LlmJudgeEvaluator(new FakeJudgeModel(evalCase.fakeJudgeJson));
            LlmJudgeResult result = evaluator.judge(evalCase);
            report.record(evalCase, result);

            if (result.isPassed() != evalCase.expectedPass) {
                failures.add(failureMessage(evalCase, result));
            }
        }

        System.out.println(report.toText());
        report.writeJson();
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    void shouldBuildJudgePromptWithFourDimensions() {
        LlmJudgeEvalCase evalCase = new LlmJudgeEvalCase();
        evalCase.userMessage = "超过七天还能退吗？";
        evalCase.candidateReply = "超过 7 天后通常不再默认支持七天无理由退货。";
        evalCase.referenceFacts = List.of("超过 7 天后不再默认支持七天无理由退货");

        String prompt = LlmJudgePromptBuilder.build(evalCase);

        assertTrue(prompt.contains("accuracyScore"));
        assertTrue(prompt.contains("completenessScore"));
        assertTrue(prompt.contains("toneScore"));
        assertTrue(prompt.contains("safetyScore"));
        assertTrue(prompt.contains(evalCase.userMessage));
        assertTrue(prompt.contains(evalCase.candidateReply));
    }

    private List<LlmJudgeEvalCase> loadCases() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream inputStream = getClass().getResourceAsStream("/eval/llm_judge_eval_cases.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("评测用例文件不存在：/eval/llm_judge_eval_cases.json");
            }
            return objectMapper.readValue(inputStream, new TypeReference<List<LlmJudgeEvalCase>>() {
            });
        }
    }

    private String failureMessage(LlmJudgeEvalCase evalCase, LlmJudgeResult result) {
        return "LLM-as-Judge 评测判断不符合预期：" + evalCase.id + "\n" +
                "  expectedPass=" + evalCase.expectedPass + "\n" +
                "  actualPass=" + result.isPassed() + "\n" +
                "  overallScore=" + result.getOverallScore() + "\n" +
                "  reason=" + result.getReason() + "\n" +
                "  issues=" + result.getIssues();
    }

    /**
     * 测试用 Judge 模型。
     *
     * 它不理解 prompt，只返回用例里预设的 JSON。
     * 这样测试重点就落在“评测框架是否稳定”上，而不是模型输出是否稳定。
     */
    private static class FakeJudgeModel implements ChatLanguageModel {
        private final String reply;

        private FakeJudgeModel(String reply) {
            this.reply = reply == null ? "{}" : reply;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            return Response.from(AiMessage.from(reply));
        }
    }

    private static class LlmJudgeReport {
        private int total;
        private int passed;
        private int expectationMatched;
        private int accuracyScoreSum;
        private int completenessScoreSum;
        private int toneScoreSum;
        private int safetyScoreSum;
        private double overallScoreSum;
        private final List<Map<String, Object>> caseRows = new ArrayList<>();

        private void record(LlmJudgeEvalCase evalCase, LlmJudgeResult result) {
            total++;
            if (result.isPassed()) {
                passed++;
            }
            if (result.isPassed() == evalCase.expectedPass) {
                expectationMatched++;
            }

            accuracyScoreSum += result.getAccuracyScore();
            completenessScoreSum += result.getCompletenessScore();
            toneScoreSum += result.getToneScore();
            safetyScoreSum += result.getSafetyScore();
            overallScoreSum += result.getOverallScore();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", evalCase.id);
            row.put("expectedPass", evalCase.expectedPass);
            row.put("actualPass", result.isPassed());
            row.put("expectationMatched", result.isPassed() == evalCase.expectedPass);
            row.put("accuracyScore", result.getAccuracyScore());
            row.put("completenessScore", result.getCompletenessScore());
            row.put("toneScore", result.getToneScore());
            row.put("safetyScore", result.getSafetyScore());
            row.put("overallScore", result.getOverallScore());
            row.put("reason", result.getReason());
            row.put("issues", result.getIssues());
            row.put("userMessage", evalCase.userMessage);
            row.put("candidateReply", evalCase.candidateReply);
            row.put("referenceFacts", evalCase.referenceFacts);
            caseRows.add(row);
        }

        private String toText() {
            return "\nLLM-as-Judge 评测报告\n" +
                    "总用例数：" + total + "\n" +
                    "通过率：" + percent(passed, total) + "\n" +
                    "判断一致率：" + percent(expectationMatched, total) + "\n" +
                    "平均总分：" + format(avg(overallScoreSum)) + "\n";
        }

        private void writeJson() throws IOException {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", total);
            summary.put("passed", passed);
            summary.put("passRate", EvalReportWriter.rate(passed, total));
            summary.put("expectationMatched", expectationMatched);
            summary.put("expectationMatchRate", EvalReportWriter.rate(expectationMatched, total));
            summary.put("averageAccuracyScore", avg(accuracyScoreSum));
            summary.put("averageCompletenessScore", avg(completenessScoreSum));
            summary.put("averageToneScore", avg(toneScoreSum));
            summary.put("averageSafetyScore", avg(safetyScoreSum));
            summary.put("averageOverallScore", avg(overallScoreSum));
            EvalReportWriter.write("llm-as-judge", summary, caseRows);
        }

        private double avg(double sum) {
            if (total == 0) {
                return 0.0;
            }
            return sum / total;
        }

        private String percent(int value, int total) {
            if (total == 0) {
                return "N/A";
            }
            return "%.1f%% (%d/%d)".formatted(value * 100.0 / total, value, total);
        }

        private String format(double value) {
            return "%.2f".formatted(value);
        }
    }
}
