package com.smartcs.smart_cs_agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.action.AgentAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSkill;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客服 Agent 离线评测测试。
 *
 * 【为什么要有这个测试？】
 * 单元测试通常验证某个很小的函数；
 * 评测测试更像一组“业务回归用例”，用来回答：
 * - 用户这样说时，意图有没有识别对？
 * - 有没有调用期望工具？
 * - 该转人工时有没有转？
 * - 多轮任务最后有没有走到正确工具？
 *
 * 第一版评测只覆盖 Skill 决策层，不调用真实 LLM。
 * 这样可以先把 Agent 的“路由、工具、转人工”稳定性做扎实。
 */
class CustomerServiceEvaluationTest {

    private final CustomerServiceSkill skill = new CustomerServiceSkill(null);

    @Test
    void shouldPassCustomerServiceRoutingEvaluation() throws IOException {
        List<EvalCase> cases = loadCases();
        EvalReport report = new EvalReport();
        List<String> failures = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            EvalResult result = runCase(evalCase);
            report.record(evalCase, result);

            if (!result.passed()) {
                failures.add(result.failureMessage());
            }
        }

        // 这段输出在本地跑测试时很有用，可以快速看到当前 Agent 决策质量。
        System.out.println(report.toText());
        report.writeJson();

        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private EvalResult runCase(EvalCase evalCase) {
        ConversationState state = new ConversationState(evalCase.turns.get(0), "USER-001");
        AgentAction action = null;

        for (int i = 0; i < evalCase.turns.size(); i++) {
            String message = evalCase.turns.get(i);
            if (i > 0) {
                state.startNewTurn(message);
            }
            action = skill.nextAction(turn(evalCase.id, state));
        }

        String actualAction = action == null ? null : action.getClass().getSimpleName();
        String actualCapability = null;
        boolean actualHandoff = action instanceof HandoffAction;

        if (action instanceof CapabilityAction capabilityAction) {
            actualCapability = capabilityAction.getCapability();
        }

        return new EvalResult(
                evalCase,
                state.getActiveIntent(),
                actualAction,
                actualCapability,
                actualHandoff
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

    private TurnContext turn(String conversationId, ConversationState state) {
        ChatRequest request = new ChatRequest(conversationId, state.getUserId(), state.getMessage());
        return new TurnContext(request, state);
    }

    /**
     * JSON 评测用例。
     *
     * 字段保持 public，是为了让 Jackson 在测试里可以直接反序列化。
     */
    public static class EvalCase {
        public String id;
        public List<String> turns;
        public String expectedIntent;
        public String expectedAction;
        public String expectedCapability;
        public boolean expectedHandoff;
    }

    private record EvalResult(EvalCase evalCase,
                              String actualIntent,
                              String actualAction,
                              String actualCapability,
                              boolean actualHandoff) {

        private boolean actionPassed() {
            return equalsNullable(evalCase.expectedAction, actualAction);
        }

        private boolean intentPassed() {
            return equalsNullable(evalCase.expectedIntent, actualIntent);
        }

        private boolean capabilityPassed() {
            return equalsNullable(evalCase.expectedCapability, actualCapability);
        }

        private boolean handoffPassed() {
            return evalCase.expectedHandoff == actualHandoff;
        }

        private boolean passed() {
            return actionPassed() && intentPassed() && capabilityPassed() && handoffPassed();
        }

        private String failureMessage() {
            return "评测失败：" + evalCase.id + "\n" +
                    "  expectedIntent=" + evalCase.expectedIntent + ", actualIntent=" + actualIntent + "\n" +
                    "  expectedAction=" + evalCase.expectedAction + ", actualAction=" + actualAction + "\n" +
                    "  expectedCapability=" + evalCase.expectedCapability + ", actualCapability=" + actualCapability + "\n" +
                    "  expectedHandoff=" + evalCase.expectedHandoff + ", actualHandoff=" + actualHandoff;
        }

        private static boolean equalsNullable(String expected, String actual) {
            if (expected == null || expected.isBlank()) {
                return actual == null || actual.isBlank();
            }
            return expected.equals(actual);
        }
    }

    /**
     * 评测报告。
     *
     * 当前先统计四个核心指标：
     * - intentAccuracy：意图命中率
     * - actionAccuracy：Action 类型命中率
     * - capabilityAccuracy：工具命中率
     * - handoffAccuracy：转人工判断命中率
     */
    private static class EvalReport {
        private int total;
        private int intentPassed;
        private int actionPassed;
        private int capabilityTotal;
        private int capabilityPassed;
        private int handoffPassed;
        private final List<Map<String, Object>> caseRows = new ArrayList<>();

        private void record(EvalCase evalCase, EvalResult result) {
            total++;
            if (result.intentPassed()) {
                intentPassed++;
            }
            if (result.actionPassed()) {
                actionPassed++;
            }
            if (evalCase.expectedCapability != null && !evalCase.expectedCapability.isBlank()) {
                capabilityTotal++;
                if (result.capabilityPassed()) {
                    capabilityPassed++;
                }
            }
            if (result.handoffPassed()) {
                handoffPassed++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", evalCase.id);
            row.put("passed", result.passed());
            row.put("expectedIntent", evalCase.expectedIntent);
            row.put("actualIntent", result.actualIntent());
            row.put("expectedAction", evalCase.expectedAction);
            row.put("actualAction", result.actualAction());
            row.put("expectedCapability", evalCase.expectedCapability);
            row.put("actualCapability", result.actualCapability());
            row.put("expectedHandoff", evalCase.expectedHandoff);
            row.put("actualHandoff", result.actualHandoff());
            caseRows.add(row);
        }

        private String toText() {
            return "\n客服 Agent 路由评测报告\n" +
                    "总用例数：" + total + "\n" +
                    "意图命中率：" + percent(intentPassed, total) + "\n" +
                    "Action 命中率：" + percent(actionPassed, total) + "\n" +
                    "工具命中率：" + percent(capabilityPassed, capabilityTotal) + "\n" +
                    "转人工命中率：" + percent(handoffPassed, total) + "\n";
        }

        private void writeJson() throws IOException {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", total);
            summary.put("intentPassed", intentPassed);
            summary.put("intentAccuracy", EvalReportWriter.rate(intentPassed, total));
            summary.put("actionPassed", actionPassed);
            summary.put("actionAccuracy", EvalReportWriter.rate(actionPassed, total));
            summary.put("capabilityTotal", capabilityTotal);
            summary.put("capabilityPassed", capabilityPassed);
            summary.put("capabilityAccuracy", EvalReportWriter.rate(capabilityPassed, capabilityTotal));
            summary.put("handoffPassed", handoffPassed);
            summary.put("handoffAccuracy", EvalReportWriter.rate(handoffPassed, total));
            EvalReportWriter.write("customer-service-routing", summary, caseRows);
        }

        private String percent(int passed, int total) {
            if (total == 0) {
                return "N/A";
            }
            return "%.1f%% (%d/%d)".formatted(passed * 100.0 / total, passed, total);
        }
    }
}
