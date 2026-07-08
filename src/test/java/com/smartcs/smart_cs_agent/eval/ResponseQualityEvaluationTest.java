package com.smartcs.smart_cs_agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回复质量评测第一版。
 *
 * 【当前做什么？】
 * 第一版先不用 LLM-as-Judge，而是使用规则评测：
 * - requiredKeywords：回复必须覆盖的关键点
 * - forbiddenKeywords：回复不能出现的危险承诺或内部信息
 *
 * 这样做虽然朴素，但稳定、便宜、可解释。
 * 后续可以把真实 LLM 回复保存成 candidateReply，再复用这套评测。
 */
class ResponseQualityEvaluationTest {

    @Test
    void shouldPassResponseQualityEvaluation() throws IOException {
        List<ResponseQualityCase> cases = loadCases();
        ResponseQualityReport report = new ResponseQualityReport();
        List<String> failures = new ArrayList<>();

        for (ResponseQualityCase evalCase : cases) {
            ResponseQualityRuleEvaluator.Result result = ResponseQualityRuleEvaluator.evaluate(
                    evalCase.candidateReply,
                    evalCase.requiredKeywords,
                    evalCase.forbiddenKeywords
            );
            report.record(evalCase, result);

            if (result.passed() != evalCase.expectedPass) {
                failures.add(failureMessage(evalCase, result));
            }
        }

        System.out.println(report.toText());
        report.writeJson();
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private List<ResponseQualityCase> loadCases() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        String overrideFile = System.getProperty("smartcs.eval.response-quality-file");
        if (overrideFile != null && !overrideFile.isBlank()) {
            Path path = Path.of(overrideFile);
            if (!Files.exists(path)) {
                throw new IllegalStateException("Response quality eval override file does not exist: " + path);
            }
            return objectMapper.readValue(path.toFile(), new TypeReference<List<ResponseQualityCase>>() {
            });
        }

        try (InputStream inputStream = getClass().getResourceAsStream("/eval/response_quality_eval_cases.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("评测用例文件不存在：/eval/response_quality_eval_cases.json");
            }
            return objectMapper.readValue(inputStream, new TypeReference<List<ResponseQualityCase>>() {
            });
        }
    }

    /**
     * JSON 回复质量评测用例。
     */
    public static class ResponseQualityCase {
        public String id;
        public String candidateReply;
        public List<String> requiredKeywords;
        public List<String> forbiddenKeywords;
        public boolean expectedPass;
    }

    private String failureMessage(ResponseQualityCase evalCase, ResponseQualityRuleEvaluator.Result result) {
        return "回复质量评测判断不符合预期：" + evalCase.id + "\n" +
                "  expectedPass=" + evalCase.expectedPass + "\n" +
                "  actualPass=" + result.passed() + "\n" +
                "  missingRequiredKeywords=" + result.missingRequiredKeywords() + "\n" +
                "  hitForbiddenKeywords=" + result.hitForbiddenKeywords() + "\n" +
                "  candidateReply=" + evalCase.candidateReply;
    }

    private static class ResponseQualityReport {
        private int total;
        private int expectationMatched;
        private int positiveTotal;
        private int positivePassed;
        private int negativeTotal;
        private int negativeBlocked;
        private final List<Map<String, Object>> caseRows = new ArrayList<>();

        private void record(ResponseQualityCase evalCase, ResponseQualityRuleEvaluator.Result result) {
            total++;
            if (result.passed() == evalCase.expectedPass) {
                expectationMatched++;
            }

            if (evalCase.expectedPass) {
                positiveTotal++;
                if (result.passed()) {
                    positivePassed++;
                }
            } else {
                negativeTotal++;
                if (!result.passed()) {
                    negativeBlocked++;
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", evalCase.id);
            row.put("expectedPass", evalCase.expectedPass);
            row.put("actualPass", result.passed());
            row.put("expectationMatched", result.passed() == evalCase.expectedPass);
            row.put("requiredKeywords", evalCase.requiredKeywords);
            row.put("forbiddenKeywords", evalCase.forbiddenKeywords);
            row.put("missingRequiredKeywords", result.missingRequiredKeywords());
            row.put("hitForbiddenKeywords", result.hitForbiddenKeywords());
            caseRows.add(row);
        }

        private String toText() {
            return "\n回复质量评测报告\n" +
                    "总用例数：" + total + "\n" +
                    "判断一致率：" + percent(expectationMatched, total) + "\n" +
                    "正例通过率：" + percent(positivePassed, positiveTotal) + "\n" +
                    "负例拦截率：" + percent(negativeBlocked, negativeTotal) + "\n";
        }

        private void writeJson() throws IOException {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", total);
            summary.put("expectationMatched", expectationMatched);
            summary.put("expectationMatchRate", EvalReportWriter.rate(expectationMatched, total));
            summary.put("positiveTotal", positiveTotal);
            summary.put("positivePassed", positivePassed);
            summary.put("positivePassRate", EvalReportWriter.rate(positivePassed, positiveTotal));
            summary.put("negativeTotal", negativeTotal);
            summary.put("negativeBlocked", negativeBlocked);
            summary.put("negativeBlockRate", EvalReportWriter.rate(negativeBlocked, negativeTotal));
            EvalReportWriter.write("response-quality", summary, caseRows);
        }

        private String percent(int passed, int total) {
            if (total == 0) {
                return "N/A";
            }
            return "%.1f%% (%d/%d)".formatted(passed * 100.0 / total, passed, total);
        }
    }
}
