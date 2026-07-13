package com.smartcs.smart_cs_agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.AgentRuntime;
import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.testing.WorkflowTestSupport;
import com.smartcs.smart_cs_agent.tools.HandoffTools;
import com.smartcs.smart_cs_agent.tools.KnowledgeTools;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.escalation.EscalationWorkflow;
import com.smartcs.smart_cs_agent.workflow.general.GeneralCustomerServiceWorkflow;
import com.smartcs.smart_cs_agent.workflow.orderquery.OrderQueryWorkflow;
import com.smartcs.smart_cs_agent.workflow.returnapply.ReturnApplyWorkflow;
import com.smartcs.smart_cs_agent.workflow.returnapply.ReturnApplyWorkflowProperties;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 链路回复评测第一版。
 *
 * 【和 ResponseQualityEvaluationTest 有什么区别？】
 * ResponseQualityEvaluationTest 直接评估静态 candidateReply。
 * 这个测试会真实跑过 CustomerServiceAgent、ConversationStore、Skill、Runtime 和独立 Capability Provider。
 *
 * <p>Phase 9 已移除 ToolRegistry 测试夹具，本测试通过 WorkflowTestSupport 装配与生产相同的 7 个
 * Local Provider，因此评测不会继续证明一条已经删除的兼容链。</p>
 *
 * 为了保证测试稳定，这里仍然使用 Fake LLM，不调用真实模型。
 * 所以它是“稳定的端到端链路评测”，不是线上 LLM 质量评测。
 */
class AgentResponseEvaluationTest {

    @Test
    void shouldPassAgentResponseEvaluation() throws IOException {
        List<AgentResponseCase> cases = loadCases();
        AgentResponseReport report = new AgentResponseReport();
        List<String> failures = new ArrayList<>();

        for (AgentResponseCase evalCase : cases) {
            AgentResponseResult result = runCase(evalCase);
            report.record(result);

            if (!result.passed()) {
                failures.add(result.failureMessage());
            }
        }

        System.out.println(report.toText());
        report.writeJson();
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private AgentResponseResult runCase(AgentResponseCase evalCase) {
        CustomerServiceAgent agent = createAgent();
        List<TraceEvent> allTraceEvents = new ArrayList<>();
        ChatResponse finalResponse = null;

        for (String turn : evalCase.turns) {
            String userId = evalCase.userId == null || evalCase.userId.isBlank() ? "USER-001" : evalCase.userId;
            ChatRequest request = new ChatRequest(
                    evalCase.id,
                    userId,
                    turn,
                    AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER"))
            );
            finalResponse = agent.chatWithTrace(request);
            allTraceEvents.addAll(finalResponse.getTrace());
        }

        String finalReply = finalResponse == null ? "" : finalResponse.getReply();
        ResponseQualityRuleEvaluator.Result qualityResult = ResponseQualityRuleEvaluator.evaluate(
                finalReply,
                evalCase.requiredKeywords,
                evalCase.forbiddenKeywords
        );

        List<String> actualTraceEvents = allTraceEvents.stream()
                .map(TraceEvent::getEventType)
                .toList();

        List<String> missingTraceEvents = evalCase.expectedTraceEvents == null
                ? List.of()
                : evalCase.expectedTraceEvents.stream()
                .filter(expected -> !actualTraceEvents.contains(expected))
                .toList();

        return new AgentResponseResult(evalCase, finalReply, qualityResult, actualTraceEvents, missingTraceEvents);
    }

    private List<AgentResponseCase> loadCases() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        try (InputStream inputStream = getClass().getResourceAsStream("/eval/agent_response_eval_cases.json")) {
            if (inputStream == null) {
                throw new IllegalStateException("评测用例文件不存在：/eval/agent_response_eval_cases.json");
            }
            return objectMapper.readValue(inputStream, new TypeReference<List<AgentResponseCase>>() {
            });
        }
    }

    private CustomerServiceAgent createAgent() {
        GlobalInterruptPolicy interruptPolicy = new GlobalInterruptPolicy();
        SkillManager skillManager = WorkflowTestSupport.skillManager(
                new TestSkillConfig(),
                new GeneralCustomerServiceWorkflow(),
                new OrderQueryWorkflow(interruptPolicy),
                new ReturnApplyWorkflow(interruptPolicy, new ReturnApplyWorkflowProperties()),
                new EscalationWorkflow(interruptPolicy)
        );

        ProductRepository productRepository = new ProductRepository();
        OrderRepository orderRepository = new OrderRepository();
        ReturnRepository returnRepository = new ReturnRepository();
        UserRepository userRepository = new UserRepository();

        AgentRuntime runtime = new AgentRuntime(
                skillManager,
                WorkflowTestSupport.actionHandlerRegistry(
                        skillManager,
                        new FakeChatLanguageModel("专业 Workflow 不应调用 Fake LLM"),
                        WorkflowTestSupport.localCapabilityGateway(
                                orderRepository,
                                productRepository,
                                returnRepository,
                                userRepository,
                                new KnowledgeTools(),
                                new HandoffTools()
                        )
                )
        );

        return new CustomerServiceAgent(
                runtime,
                new ConversationStore()
        );
    }

    public static class AgentResponseCase {
        public String id;
        public String userId;
        public List<String> turns;
        public List<String> requiredKeywords;
        public List<String> forbiddenKeywords;
        public List<String> expectedTraceEvents;
    }

    private record AgentResponseResult(AgentResponseCase evalCase,
                                       String finalReply,
                                       ResponseQualityRuleEvaluator.Result qualityResult,
                                       List<String> actualTraceEvents,
                                       List<String> missingTraceEvents) {

        private boolean tracePassed() {
            return missingTraceEvents.isEmpty();
        }

        private boolean passed() {
            return qualityResult.passed() && tracePassed();
        }

        private String failureMessage() {
            return "Agent 链路回复评测失败：" + evalCase.id + "\n" +
                    "  finalReply=" + finalReply + "\n" +
                    "  missingRequiredKeywords=" + qualityResult.missingRequiredKeywords() + "\n" +
                    "  hitForbiddenKeywords=" + qualityResult.hitForbiddenKeywords() + "\n" +
                    "  missingTraceEvents=" + missingTraceEvents + "\n" +
                    "  actualTraceEvents=" + actualTraceEvents;
        }
    }

    private static class AgentResponseReport {
        private int total;
        private int qualityPassed;
        private int tracePassed;
        private final List<Map<String, Object>> caseRows = new ArrayList<>();

        private void record(AgentResponseResult result) {
            total++;
            if (result.qualityResult().passed()) {
                qualityPassed++;
            }
            if (result.tracePassed()) {
                tracePassed++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", result.evalCase().id);
            row.put("passed", result.passed());
            row.put("qualityPassed", result.qualityResult().passed());
            row.put("tracePassed", result.tracePassed());
            row.put("finalReply", result.finalReply());
            row.put("requiredKeywords", result.evalCase().requiredKeywords);
            row.put("forbiddenKeywords", result.evalCase().forbiddenKeywords);
            row.put("missingRequiredKeywords", result.qualityResult().missingRequiredKeywords());
            row.put("hitForbiddenKeywords", result.qualityResult().hitForbiddenKeywords());
            row.put("expectedTraceEvents", result.evalCase().expectedTraceEvents);
            row.put("missingTraceEvents", result.missingTraceEvents());
            row.put("actualTraceEvents", result.actualTraceEvents());
            caseRows.add(row);
        }

        private String toText() {
            return "\nAgent 链路回复评测报告\n" +
                    "总用例数：" + total + "\n" +
                    "回复质量通过率：" + percent(qualityPassed, total) + "\n" +
                    "Trace 事件覆盖率：" + percent(tracePassed, total) + "\n";
        }

        private void writeJson() throws IOException {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", total);
            summary.put("qualityPassed", qualityPassed);
            summary.put("qualityPassRate", EvalReportWriter.rate(qualityPassed, total));
            summary.put("tracePassed", tracePassed);
            summary.put("traceCoverageRate", EvalReportWriter.rate(tracePassed, total));
            EvalReportWriter.write("agent-response-chain", summary, caseRows);
        }

        private String percent(int passed, int total) {
            if (total == 0) {
                return "N/A";
            }
            return "%.1f%% (%d/%d)".formatted(passed * 100.0 / total, passed, total);
        }
    }

    private static class TestSkillConfig extends SkillConfig {
        @Override
        public String getSystemPrompt() {
            return "你是测试环境中的客服助手。";
        }
    }

    private static class FakeChatLanguageModel implements ChatLanguageModel {
        private final String reply;

        private FakeChatLanguageModel(String reply) {
            this.reply = reply == null ? "" : reply;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            return Response.from(AiMessage.from(reply));
        }
    }
}
