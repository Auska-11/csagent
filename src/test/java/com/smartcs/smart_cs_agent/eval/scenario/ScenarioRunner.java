package com.smartcs.smart_cs_agent.eval.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.MonitorSummary;
import com.smartcs.smart_cs_agent.monitor.MonitorTurnSnapshot;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 场景执行器：按 76 号计划 3.5 节走真实 HTTP 部署形态。
 *
 * <p>链路：POST /api/auth/demo/tokens 取 persona JWT → 逐轮 POST /api/chat/send（同一
 * conversationId）→ 从响应反序列化 Trace → 用生产 Monitor 分类 TurnOutcome → 确定性断言。
 * 每轮执行前把 {@link ScriptedFaultChatModel} 切到场景声明的故障模式，轮末恢复 NORMAL。</p>
 *
 * <p>MockMvc 会穿过真实 SecurityFilterChain、JWT 校验、Controller、Agent Runtime 到
 * Capability Provider，等价于部署形态的请求处理，只是不开监听端口。</p>
 */
class ScenarioRunner {

    static final String WORKFLOW_ENDPOINT = "/api/chat/send";
    static final String BASELINE_ENDPOINT = "/api/chat/baseline/send";

    private final MockMvc mockMvc;
    private final String chatEndpoint;
    private final EndpointSide side;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScenarioAssertionEngine assertionEngine = new ScenarioAssertionEngine();
    private final Map<String, String> personaTokens = new HashMap<>();

    ScenarioRunner(MockMvc mockMvc) {
        this(mockMvc, WORKFLOW_ENDPOINT);
    }

    /** 指定被测方端点：Workflow 用 {@link #WORKFLOW_ENDPOINT}，基线用 {@link #BASELINE_ENDPOINT}。 */
    ScenarioRunner(MockMvc mockMvc, String chatEndpoint) {
        this.mockMvc = mockMvc;
        this.chatEndpoint = chatEndpoint;
        this.side = EndpointSide.ofEndpoint(chatEndpoint);
    }

    /** 本执行器代表的被测端；断言引擎据此决定端专属断言是否生效。 */
    EndpointSide side() {
        return side;
    }

    /** 从 classpath 加载场景脚本文件。 */
    List<Scenario> loadScenarios(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("场景文件不存在：" + resourcePath);
            }
            return objectMapper.readValue(
                    inputStream,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Scenario.class)
            );
        }
    }

    /** 按顺序加载并拼接多个场景文件（M3 的 60 条按类别分六个文件维护）。 */
    List<Scenario> loadScenarios(List<String> resourcePaths) throws IOException {
        List<Scenario> scenarios = new ArrayList<>();
        for (String path : resourcePaths) {
            scenarios.addAll(loadScenarios(path));
        }
        return scenarios;
    }

    /** 执行全部场景，返回每次执行的行记录（场景 × 重复次数）。 */
    List<ScenarioRunResult> runAll(List<Scenario> scenarios) throws Exception {
        return runAll(scenarios, null);
    }

    /**
     * M4 真实跑批变体：按 (场景, repeat) 跳过已完成的执行（断点续跑），
     * 且每个实际执行的场景前刷新 persona JWT——真实模型一轮跑批可能远超
     * token TTL，Fake 下的“整个测试方法取一次”策略不再安全。
     */
    List<ScenarioRunResult> runAll(List<Scenario> scenarios,
                                   java.util.function.BiPredicate<Scenario, Integer> shouldRun) throws Exception {
        loadPersonaTokens();
        List<ScenarioRunResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            boolean firstRunOfScenario = true;
            for (int repeat = 1; repeat <= scenario.repeatCount(); repeat++) {
                if (shouldRun != null && !shouldRun.test(scenario, repeat)) {
                    continue;
                }
                if (!firstRunOfScenario) {
                    loadPersonaTokens();
                }
                results.add(runOnce(scenario, repeat));
                firstRunOfScenario = false;
            }
        }
        return results;
    }

    /** persona JWT 只取一次；demo tokens 有 TTL，同一测试方法内复用足够。 */
    private void loadPersonaTokens() throws Exception {
        personaTokens.clear();
        MvcResult result = mockMvc.perform(post("/api/auth/demo/tokens"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tokens = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        if (!tokens.isArray()) {
            throw new IllegalStateException("/api/auth/demo/tokens 应返回数组");
        }
        for (JsonNode token : tokens) {
            personaTokens.put(token.path("userId").asText(), token.path("token").asText());
        }
    }

    private ScenarioRunResult runOnce(Scenario scenario, int repeat) throws Exception {
        List<String> violations = new ArrayList<>();
        String personaUserId = scenario.persona == null ? "" : scenario.persona.getUserId();
        String token = personaTokens.get(personaUserId);
        if (token == null) {
            violations.add("persona-token-missing: /api/auth/demo/tokens 中没有身份 [" + personaUserId + "]");
            return new ScenarioRunResult(scenario, repeat, List.of(), violations, false);
        }

        // 每次执行使用全新 conversationId，隔离会话状态；同一 JVM 里重复跑测试也不会互相污染。
        String conversationId = "scn-" + scenario.getId() + "-r" + repeat + "-"
                + UUID.randomUUID().toString().substring(0, 8);

        List<TurnExecutionRecord> records = new ArrayList<>();
        List<ScenarioTurn> turns = scenario.getTurns();
        for (int i = 0; i < turns.size(); i++) {
            ScenarioTurn turn = turns.get(i);
            records.add(executeTurn(i + 1, conversationId, personaUserId, token, turn));
        }

        violations.addAll(assertionEngine.evaluateFinal(scenario, records, side));
        boolean passed = violations.isEmpty()
                && records.stream().allMatch(record -> record.violations().isEmpty());
        return new ScenarioRunResult(scenario, repeat, records, violations, passed);
    }

    private TurnExecutionRecord executeTurn(int index,
                                            String conversationId,
                                            String personaUserId,
                                            String token,
                                            ScenarioTurn turn) throws Exception {
        ScriptedFaultChatModel.FaultMode mode = faultModeOf(turn.getFaultInjection());
        ScriptedFaultChatModel.setMode(mode);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", turn.getMessage());
            body.put("conversationId", conversationId);
            MvcResult result = mockMvc.perform(post(chatEndpoint)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andReturn();

            int httpStatus = result.getResponse().getStatus();
            ChatExchange exchange = parseExchange(result);
            return buildRecord(index, turn, httpStatus, exchange, personaUserId, conversationId);
        } finally {
            // 无论本轮结果如何都要恢复 NORMAL，防止故障模式泄漏到后续场景。
            ScriptedFaultChatModel.setMode(ScriptedFaultChatModel.FaultMode.NORMAL);
        }
    }

    private TurnExecutionRecord buildRecord(int index,
                                            ScenarioTurn turn,
                                            int httpStatus,
                                            ChatExchange exchange,
                                            String personaUserId,
                                            String conversationId) {
        List<TraceEvent> trace = exchange.trace();
        String reply = exchange.reply();

        String outcome;
        String errorCode;
        if (httpStatus != 200) {
            // 非 200 时没有 Trace，不能让 Monitor 的“无记录”误判成 SUCCESS：
            // 基线在 LLM 故障下整轮 503 是对照实验的核心事实，必须按 TECHNICAL_FAILURE 记账。
            outcome = "TECHNICAL_FAILURE";
            errorCode = exchange.httpErrorCode();
        } else {
            // 复用生产 Monitor 的 Trace→Outcome 分类，保证场景判定与线上监控口径一致。
            MonitorService monitorService = new MonitorService();
            monitorService.recordTurn(new ChatResponse(conversationId, personaUserId, reply, trace));
            MonitorSummary summary = monitorService.getSummary();
            MonitorTurnSnapshot latest = summary.getRecentTurns().isEmpty()
                    ? null
                    : summary.getRecentTurns().get(0);
            outcome = latest == null ? "SUCCESS" : latest.getOutcome().name();
            errorCode = latest == null ? "" : latest.getErrorCode();
        }

        List<String> triggeredCapabilities = trace.stream()
                .filter(event -> "tool_called".equals(event.getEventType()))
                .map(event -> String.valueOf(event.getData().get("capability")))
                .distinct()
                .toList();
        boolean llmFailed = trace.stream()
                .anyMatch(event -> "llm_failed".equals(event.getEventType()));

        List<String> turnViolations = assertionEngine.evaluateTurn(turn,
                new TurnExecutionRecord(
                        index,
                        turn.getMessage(),
                        httpStatus,
                        reply,
                        trace,
                        outcome,
                        errorCode,
                        triggeredCapabilities,
                        llmFailed,
                        List.of()
                ),
                side);
        return new TurnExecutionRecord(
                index,
                turn.getMessage(),
                httpStatus,
                reply,
                trace,
                outcome,
                errorCode,
                triggeredCapabilities,
                llmFailed,
                turnViolations
        );
    }

    private ScriptedFaultChatModel.FaultMode faultModeOf(ScenarioFaultInjection faultInjection) {
        if (faultInjection == null || faultInjection.isNone()) {
            return ScriptedFaultChatModel.FaultMode.NORMAL;
        }
        if (faultInjection.hasLlmFailure()) {
            return ScriptedFaultChatModel.FaultMode.FAILURE;
        }
        return ScriptedFaultChatModel.FaultMode.TIMEOUT;
    }

    /** 解析 /api/chat/send 响应：200 取 reply + trace；非 200 记录 errorCode 便于排障。 */
    private ChatExchange parseExchange(MvcResult result) throws UnsupportedEncodingException, IOException {
        // 必须显式 UTF-8：默认按 ISO-8859-1 解码会把中文回复变成乱码，导致所有文案断言失真。
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (result.getResponse().getStatus() != 200) {
            String errorCode = "";
            try {
                errorCode = objectMapper.readTree(content).path("errorCode").asText("");
            } catch (IOException ignored) {
                // 非 JSON 错误体只保留状态码。
            }
            return new ChatExchange("", List.of(), errorCode);
        }
        JsonNode root = objectMapper.readTree(content);
        String reply = root.path("reply").asText("");
        List<TraceEvent> trace = new ArrayList<>();
        for (JsonNode eventNode : root.path("trace")) {
            Map<String, Object> data = new LinkedHashMap<>();
            // convertValue 把 JsonNode 还原成原生 String/Map/List/Number；
            // 直接存 JsonNode 会让 data.get("capability") 变成 "[OBJECT:TextNode]" 这类调试串，
            // 能力断言和 TurnOutcomeClassifier 的 errorCode 读取都会失真。
            eventNode.path("data").properties()
                    .forEach(entry -> data.put(entry.getKey(),
                            objectMapper.convertValue(entry.getValue(), Object.class)));
            trace.add(new TraceEvent(
                    eventNode.path("eventType").asText(""),
                    eventNode.path("step").asInt(0),
                    eventNode.path("message").asText(""),
                    data,
                    eventNode.path("timestamp").asLong(0)
            ));
        }
        return new ChatExchange(reply, trace, "");
    }

    /** HTTP 交换的解析结果；httpErrorCode 只在非 200 时非空。 */
    private record ChatExchange(String reply, List<TraceEvent> trace, String httpErrorCode) {
    }
}
