package com.smartcs.smart_cs_agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouteResult;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客服 Agent 首次 Workflow 路由离线评测。
 *
 * <p>Phase 9 之前，这组数据直接调用单体 CustomerServiceSkill，所以只能证明旧类内部的关键词分支。
 * 现在测试使用 Spring 实际注册的全部 Workflow 和正式 WorkflowRouter，评测结果与生产选择链一致。</p>
 *
 * <p>多轮状态恢复由各 Workflow 集成测试覆盖；本评测只取 turns 第一条消息验证“首次应该交给谁”，
 * 避免在一个指标里混入 Capability、身份和副作用执行结果。</p>
 */
@SpringBootTest
class CustomerServiceEvaluationTest {

    @Autowired
    private WorkflowRouter workflowRouter;

    @Test
    void shouldPassCustomerServiceRoutingEvaluation() throws IOException {
        List<EvalCase> cases = loadCases();
        EvalReport report = new EvalReport();
        List<String> failures = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            EvalResult result = runCase(evalCase);
            report.record(result);
            if (!result.passed()) {
                failures.add(result.failureMessage());
            }
        }

        System.out.println(report.toText());
        report.writeJson();
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private EvalResult runCase(EvalCase evalCase) {
        String message = evalCase.turns.get(0);
        String conversationId = "routing-eval-" + evalCase.id;
        ChatRequest request = new ChatRequest(conversationId, "USER-EVAL", message);
        WorkflowRouteResult route = workflowRouter.route(
                request,
                new ConversationState(message, "USER-EVAL")
        );
        return new EvalResult(
                evalCase,
                route.getSelectedWorkflow().descriptor().getId(),
                route.getSelectedScore(),
                route.getReason()
        );
    }

    private List<EvalCase> loadCases() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream inputStream = getClass().getResourceAsStream("/eval/customer_service_eval_cases.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("评测用例文件不存在：/eval/customer_service_eval_cases.json");
            }
            return objectMapper.readValue(inputStream, new TypeReference<List<EvalCase>>() {
            });
        }
    }

    /** JSON 字段保持 public，方便 Jackson 在测试中直接反序列化。 */
    public static class EvalCase {
        public String id;
        public List<String> turns;
        public String expectedWorkflow;
    }

    private record EvalResult(EvalCase evalCase,
                              String actualWorkflow,
                              double selectedScore,
                              String reason) {

        private boolean passed() {
            return evalCase.expectedWorkflow.equals(actualWorkflow);
        }

        private String failureMessage() {
            return "路由评测失败：" + evalCase.id + "\n"
                    + "  expectedWorkflow=" + evalCase.expectedWorkflow
                    + ", actualWorkflow=" + actualWorkflow + "\n"
                    + "  score=" + selectedScore + ", reason=" + reason;
        }
    }

    /** 只统计一个含义明确的核心指标，候选分和原因保留在逐用例 JSON 中用于排查。 */
    private static class EvalReport {
        private int total;
        private int passed;
        private final List<Map<String, Object>> caseRows = new ArrayList<>();

        private void record(EvalResult result) {
            total++;
            if (result.passed()) {
                passed++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", result.evalCase().id);
            row.put("passed", result.passed());
            row.put("expectedWorkflow", result.evalCase().expectedWorkflow);
            row.put("actualWorkflow", result.actualWorkflow());
            row.put("selectedScore", result.selectedScore());
            row.put("reason", result.reason());
            caseRows.add(row);
        }

        private String toText() {
            return "\n客服 Agent Workflow 路由评测报告\n"
                    + "总用例数：" + total + "\n"
                    + "Workflow 命中率：" + percent(passed, total) + "\n";
        }

        private void writeJson() throws IOException {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", total);
            summary.put("workflowPassed", passed);
            summary.put("workflowAccuracy", EvalReportWriter.rate(passed, total));
            EvalReportWriter.write("customer-service-routing", summary, caseRows);
        }

        private String percent(int value, int count) {
            return count == 0 ? "N/A" : "%.1f%% (%d/%d)".formatted(value * 100.0 / count, value, count);
        }
    }
}
