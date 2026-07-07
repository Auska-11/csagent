package com.smartcs.smart_cs_agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.tools.KnowledgeSearchResult;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库召回评测。
 *
 * 【为什么单独做召回评测？】
 * 路由评测只能证明“政策问题会调用 knowledge_search”，
 * 但不能证明“knowledge_search 真的找到了正确文档”。
 *
 * 这个测试直接运行 KnowledgeTools.search(...)，检查：
 * - topK 结果是否包含期望文档
 * - 检索文本是否覆盖关键政策点
 */
class KnowledgeRecallEvaluationTest {

    private final KnowledgeTools knowledgeTools = new KnowledgeTools();

    @Test
    void shouldPassKnowledgeRecallEvaluation() throws IOException {
        List<KnowledgeRecallCase> cases = loadCases();
        KnowledgeRecallReport report = new KnowledgeRecallReport();
        List<String> failures = new ArrayList<>();

        for (KnowledgeRecallCase evalCase : cases) {
            KnowledgeRecallResult result = runCase(evalCase);
            report.record(result);

            if (!result.passed()) {
                failures.add(result.failureMessage());
            }
        }

        System.out.println(report.toText());
        report.writeJson();
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private KnowledgeRecallResult runCase(KnowledgeRecallCase evalCase) {
        int topK = evalCase.topK <= 0 ? 3 : evalCase.topK;
        List<KnowledgeSearchResult> results = knowledgeTools.search(evalCase.query, topK);
        String promptText = knowledgeTools.formatSearchResult(evalCase.query, results);

        boolean documentHit = results.stream()
                .anyMatch(result -> evalCase.expectedDocumentId.equals(result.getDocumentId()));

        List<String> missingKeywords = evalCase.expectedKeywords == null
                ? List.of()
                : evalCase.expectedKeywords.stream()
                .filter(keyword -> !promptText.contains(keyword))
                .toList();

        return new KnowledgeRecallResult(evalCase, results, documentHit, missingKeywords);
    }

    private List<KnowledgeRecallCase> loadCases() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream inputStream = getClass().getResourceAsStream("/eval/knowledge_recall_eval_cases.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("评测用例文件不存在：/eval/knowledge_recall_eval_cases.json");
            }
            return objectMapper.readValue(inputStream, new TypeReference<List<KnowledgeRecallCase>>() {
            });
        }
    }

    /**
     * JSON 召回评测用例。
     */
    public static class KnowledgeRecallCase {
        public String id;
        public String query;
        public String expectedDocumentId;
        public List<String> expectedKeywords;
        public int topK;
    }

    private record KnowledgeRecallResult(KnowledgeRecallCase evalCase,
                                         List<KnowledgeSearchResult> results,
                                         boolean documentHit,
                                         List<String> missingKeywords) {

        private boolean keywordPassed() {
            return missingKeywords.isEmpty();
        }

        private boolean passed() {
            return documentHit && keywordPassed();
        }

        private String failureMessage() {
            return "知识库召回评测失败：" + evalCase.id + "\n" +
                    "  query=" + evalCase.query + "\n" +
                    "  expectedDocumentId=" + evalCase.expectedDocumentId + "\n" +
                    "  actualDocumentIds=" + actualDocumentIds() + "\n" +
                    "  missingKeywords=" + missingKeywords;
        }

        private List<String> actualDocumentIds() {
            return results.stream()
                    .map(KnowledgeSearchResult::getDocumentId)
                    .toList();
        }
    }

    private static class KnowledgeRecallReport {
        private int total;
        private int documentHit;
        private int keywordPassed;
        private final List<Map<String, Object>> caseRows = new ArrayList<>();

        private void record(KnowledgeRecallResult result) {
            total++;
            if (result.documentHit()) {
                documentHit++;
            }
            if (result.keywordPassed()) {
                keywordPassed++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", result.evalCase().id);
            row.put("query", result.evalCase().query);
            row.put("passed", result.passed());
            row.put("expectedDocumentId", result.evalCase().expectedDocumentId);
            row.put("actualDocumentIds", result.actualDocumentIds());
            row.put("documentHit", result.documentHit());
            row.put("expectedKeywords", result.evalCase().expectedKeywords);
            row.put("missingKeywords", result.missingKeywords());
            row.put("topResults", result.results().stream()
                    .map(searchResult -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("documentId", searchResult.getDocumentId());
                        item.put("title", searchResult.getTitle());
                        item.put("category", searchResult.getCategory());
                        item.put("score", searchResult.getScore());
                        return item;
                    })
                    .toList());
            caseRows.add(row);
        }

        private String toText() {
            return "\n知识库召回评测报告\n" +
                    "总用例数：" + total + "\n" +
                    "文档召回率：" + percent(documentHit, total) + "\n" +
                    "关键词覆盖率：" + percent(keywordPassed, total) + "\n";
        }

        private void writeJson() throws IOException {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", total);
            summary.put("documentHit", documentHit);
            summary.put("documentRecall", EvalReportWriter.rate(documentHit, total));
            summary.put("keywordPassed", keywordPassed);
            summary.put("keywordCoverage", EvalReportWriter.rate(keywordPassed, total));
            EvalReportWriter.write("knowledge-recall", summary, caseRows);
        }

        private String percent(int passed, int total) {
            if (total == 0) {
                return "N/A";
            }
            return "%.1f%% (%d/%d)".formatted(passed * 100.0 / total, passed, total);
        }
    }
}
