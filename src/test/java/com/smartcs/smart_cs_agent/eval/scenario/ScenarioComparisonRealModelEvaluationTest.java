package com.smartcs.smart_cs_agent.eval.scenario;

import com.smartcs.smart_cs_agent.eval.EvalReportWriter;
import com.smartcs.smart_cs_agent.eval.OptionalRealJudgeModelFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 真实模型跑批入口（手动路径，默认关闭，CI 永不触网）。
 *
 * <p>与 {@link ScenarioComparisonEvaluationTest}（CI 结构校准）的区别：</p>
 * <ul>
 *   <li>业务模型是 OpenRouter 真实模型（配置来自 application.yml 的 langchain4j.open-ai.*
 *       解析结果，即 OPENAI_* 环境变量），两侧被测方共享同一个模型 bean（公平原则第 2 条）；</li>
 *   <li>llm 故障注入改由 {@link FaultAwareChatModel} 装饰器在真实模型外对称执行，
 *       复用 {@link ScriptedFaultChatModel} 的静态 FaultMode 开关（FAUlt 类场景依然有效）；</li>
 *   <li>盲评 Judge 是 {@link LlmBlindJudge}（同一 OpenRouter 模型，temperature 0.0，
 *       第三方视角），场景意图读 judgeIntent（身份中立）；</li>
 *   <li>不做“全部断言必须通过”的硬断言——真实模型下的失败本身就是对照数据，
 *       只做结构性校验（场景形态、盲性、执行完整性、Judge 可用率）并如实落盘报告；</li>
 *   <li>免费额度需要请求节流（默认全局最小间隔 3.5s，业务与 Judge 共享令牌桶），
 *       Judge 额外做 429/网络异常退避重试；</li>
 *   <li>等待上限对称放宽：OpenRouter 免费档存在数十秒的排队延迟，线上默认的 25s
 *       Guard 会把它变成整轮 503，测的是排队不是架构。真实跑批把 LLM 调用等待
 *       上限放宽到 90s、Agent 轮超时 150s（两侧同享，故障注入挂起时长自动跟随
 *       call-timeout+8s，仍触发 Guard 降级路径而不是整轮超时）；</li>
 *   <li>断点续跑：每次执行（端 × 场景 × repeat）完成后立即追加到 JSONL checkpoint，
 *       中断后重跑同一命令会跳过已完成执行；退货类断言已允许 SUCCESS|BUSINESS_REJECTED，
 *       跨 JVM 重跑不会因 ORD-003/ORD-004 的 DUPLICATE 消耗顺序而失真。
 *       想全新开始请删除 checkpoint 文件。</li>
 * </ul>
 *
 * <p>运行方式（本地手动；.env 含未加引号的特殊字符不能直接 source，只提取 OPENAI_* 三件套）：</p>
 * <pre>
 * export OPENAI_API_KEY=... OPENAI_BASE_URL=... OPENAI_MODEL_NAME=...   # 取自 .env
 * export SMARTCS_PERSISTENCE_MODE=memory
 * export SMARTCS_EVAL_REAL_COMPARISON_ENABLED=true
 * export SMARTCS_LLM_EXECUTION_CALL_TIMEOUT_MS=90000
 * export SMARTCS_AGENT_TURN_TIMEOUT_MS=150000
 * export OPENAI_TIMEOUT=PT85S
 * bash mvnw -P'!frontend' -Dtest=ScenarioComparisonRealModelEvaluationTest test
 * # 冒烟（单场景，独立 checkpoint/报告，不污染正式 checkpoint）：
 * #   -Dsmartcs.eval.real-comparison.only=normal-policy-return-window
 * </pre>
 */
@SpringBootTest(properties = {
        "smartcs.security.demo-issuer-enabled=true",
        "smartcs.security.jwt-secret=test-only-smart-cs-jwt-secret-32-bytes-ok"
})
@AutoConfigureMockMvc
@EnabledIf(value = "realComparisonEnabled", disabledReason = "默认关闭：真实模型跑批需要网络与 OPENAI_* 配置（76 号计划 M4 手动路径）")
class ScenarioComparisonRealModelEvaluationTest {

    private static final String ENABLED_PROPERTY = "smartcs.eval.real-comparison.enabled";
    private static final String ENABLED_ENV = "SMARTCS_EVAL_REAL_COMPARISON_ENABLED";
    private static final String ONLY_PROPERTY = "smartcs.eval.real-comparison.only";
    private static final String MIN_INTERVAL_PROPERTY = "smartcs.eval.real-comparison.min-interval-ms";
    private static final List<String> CATEGORY_ORDER = List.of(
            "NORMAL", "HARD", "ADVERSARIAL", "HALLUCINATION", "FAULT", "CHITCHAT");

    /** 全局节流器：业务模型与 Judge 模型共用，保证对 OpenRouter 的请求速率低于免费档上限。 */
    static final LlmRequestThrottle THROTTLE =
            new LlmRequestThrottle(Long.getLong(MIN_INTERVAL_PROPERTY, 3_500L));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    static boolean realComparisonEnabled() {
        String property = System.getProperty(ENABLED_PROPERTY);
        if (property != null && !property.isBlank()) {
            return "true".equalsIgnoreCase(property.trim()) || "1".equals(property.trim())
                    || "yes".equalsIgnoreCase(property.trim());
        }
        String env = System.getenv(ENABLED_ENV);
        return env != null && ("true".equalsIgnoreCase(env.trim()) || "1".equals(env.trim())
                || "yes".equalsIgnoreCase(env.trim()));
    }

    /**
     * 覆盖 langchain4j 自动装配的模型：真实 OpenRouter 模型外面包一层故障感知装饰器。
     * 参数全部从 Environment 读（与 application.yml 的 langchain4j.open-ai.chat-model.* 完全同源），
     * 保证两侧被测方与部署形态用同一模型同一采样参数。
     */
    @TestConfiguration
    static class RealFaultInjectionConfiguration {

        @Bean
        @Primary
        ChatLanguageModel faultAwareRealChatModel(Environment env) {
            OpenAiChatModel real = OpenAiChatModel.builder()
                    .apiKey(env.getProperty("langchain4j.open-ai.chat-model.api-key"))
                    .baseUrl(env.getProperty("langchain4j.open-ai.chat-model.base-url",
                            "https://api.openai.com/v1"))
                    .modelName(env.getProperty("langchain4j.open-ai.chat-model.model-name"))
                    .temperature(env.getProperty("langchain4j.open-ai.chat-model.temperature",
                            Double.class, 0.7))
                    .maxTokens(env.getProperty("langchain4j.open-ai.chat-model.max-tokens",
                            Integer.class, 2000))
                    .timeout(Duration.parse(env.getProperty("langchain4j.open-ai.chat-model.timeout",
                            "PT10S")))
                    .maxRetries(env.getProperty("langchain4j.open-ai.chat-model.max-retries",
                            Integer.class, 1))
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            long callTimeoutMs = env.getProperty("smartcs.llm.execution.call-timeout-ms",
                    Long.class, 12_000L);
            return new FaultAwareChatModel(real, callTimeoutMs + 8_000L);
        }
    }

    @Test
    void realModelComparisonBatch() throws Exception {
        String apiKey = environment.getProperty("langchain4j.open-ai.chat-model.api-key", "");
        String modelName = environment.getProperty("langchain4j.open-ai.chat-model.model-name", "");
        assertTrue(!apiKey.isBlank() && !"dummy-test-key".equals(apiKey),
                "真实跑批已开启但缺少 OPENAI_API_KEY（先 source .env）");
        assertTrue(!modelName.isBlank() && !"dummy-model".equals(modelName),
                "真实跑批已开启但缺少 OPENAI_MODEL_NAME（先 source .env）");

        ScenarioRunner workflowRunner = new ScenarioRunner(mockMvc, ScenarioRunner.WORKFLOW_ENDPOINT);
        ScenarioRunner baselineRunner = new ScenarioRunner(mockMvc, ScenarioRunner.BASELINE_ENDPOINT);
        List<Scenario> scenarios = workflowRunner.loadScenarios(
                ScenarioComparisonEvaluationTest.FULL_SCENARIO_FILES);
        scenarios = applyOnlyFilter(scenarios);
        boolean fullRun = scenarios.size() == 60;
        if (fullRun) {
            assertCatalogShape(scenarios);
        }

        boolean smoke = System.getProperty(ONLY_PROPERTY) != null;
        Path checkpoint = Path.of("target", "eval-reports",
                smoke ? "scenario-comparison-real-smoke-runs.jsonl" : "scenario-comparison-real-runs.jsonl");
        RealRunStore store = RealRunStore.load(checkpoint);

        // Judge：同一 OpenRouter 模型、temperature 0.0、匿名 A/B；失败退避重试由装饰器负责。
        LlmBlindJudge judge = new LlmBlindJudge(new ThrottledRetryChatModel(
                OptionalRealJudgeModelFactory.create()));

        int freshRuns = 0;
        for (EndpointSide side : List.of(EndpointSide.WORKFLOW, EndpointSide.BASELINE)) {
            ScenarioRunner runner = side == EndpointSide.WORKFLOW ? workflowRunner : baselineRunner;
            List<ScenarioRunResult> fresh = runner.runAll(scenarios,
                    (scenario, repeat) -> !store.has(side, scenario.getId(), repeat));
            for (ScenarioRunResult run : fresh) {
                ScenarioJudgement judgement = judge.judge(run.scenario(), run, side.blindLabel());
                store.append(side, run, judgement);
                freshRuns++;
            }
        }

        RealReport report = RealReport.build(scenarios, store.rows());
        report.writeTo(smoke ? "scenario-comparison-real-smoke" : "scenario-comparison-real", modelName);
        System.out.println(report.toText(freshRuns));

        // 结构性校验：只保证实验口径成立，不要求真实模型通过任何行为断言。
        if (fullRun) {
            for (EndpointSide side : List.of(EndpointSide.WORKFLOW, EndpointSide.BASELINE)) {
                for (Scenario scenario : scenarios) {
                    for (int repeat = 1; repeat <= scenario.repeatCount(); repeat++) {
                        assertTrue(store.has(side, scenario.getId(), repeat),
                                side + " × " + scenario.getId() + " × r" + repeat
                                        + " 缺少执行记录（断点续跑漏跑）");
                    }
                }
            }
        }
        assertJudgeHealth(store, smoke);
        assertBlindness(scenarios);
    }

    /** 冒烟子集：-Dsmartcs.eval.real-comparison.only=id1,id2；只筛选场景，不改变断言。 */
    private List<Scenario> applyOnlyFilter(List<Scenario> scenarios) {
        String only = System.getProperty(ONLY_PROPERTY);
        if (only == null || only.isBlank()) {
            return scenarios;
        }
        List<String> wanted = List.of(only.split(","));
        List<Scenario> filtered = scenarios.stream()
                .filter(scenario -> wanted.contains(scenario.getId()))
                .toList();
        assertEquals(wanted.size(), filtered.size(), "only 过滤器里有不存在的场景 ID");
        return filtered;
    }

    /**
     * Judge 可用率守门：如果超过一半判定是 judge-model-error/parse-error，
     * 数字已经没有意义（配置或额度问题），直接失败而不是产出误导性报告。
     */
    private void assertJudgeHealth(RealRunStore store, boolean smoke) {
        List<Map<String, Object>> rows = store.rows();
        long errors = rows.stream()
                .filter(row -> String.valueOf(row.get("judgeRationale")).startsWith("judge-"))
                .count();
        double errorRate = rows.isEmpty() ? 1.0 : errors * 1.0 / rows.size();
        assertTrue(errorRate < 0.5,
                "Judge 错误率 " + String.format("%.0f", errorRate * 100) + "%（" + errors + "/"
                        + rows.size() + "）——请检查 API Key/额度后再重跑（断点续跑会跳过已完成执行）");
        if (smoke) {
            System.out.println("冒烟模式：Judge 错误 " + errors + "/" + rows.size());
        }
    }

    /** 全部场景 × 两个匿名标签的盲评 prompt 必须不含身份词（公平原则第 6 条，离线可校验）。 */
    private void assertBlindness(List<Scenario> scenarios) {
        for (Scenario scenario : scenarios) {
            assertTrue(scenario.judgeIntent != null && !scenario.judgeIntent.isBlank(),
                    scenario.getId() + " 缺少身份中立的 judgeIntent，真实盲评会被 description 泄漏身份");
            ScenarioRunResult emptyRun = new ScenarioRunResult(scenario, 1, List.of(), List.of(), true);
            for (String label : List.of("Agent A", "Agent B")) {
                String prompt = LlmBlindJudge.buildPrompt(scenario, emptyRun, label);
                assertTrue(LlmBlindJudge.isBlindPrompt(prompt),
                        scenario.getId() + " 的盲评 prompt 泄漏身份词：" + prompt);
            }
        }
    }

    private void assertCatalogShape(List<Scenario> scenarios) {
        assertEquals(60, scenarios.size(), "场景总数应为 60");
        Map<String, Integer> expected = Map.of(
                "NORMAL", 10, "HARD", 10, "ADVERSARIAL", 12,
                "HALLUCINATION", 8, "FAULT", 10, "CHITCHAT", 10);
        Map<String, Integer> actual = new TreeMap<>();
        scenarios.forEach(scenario -> actual.merge(scenario.category().name(), 1, Integer::sum));
        assertEquals(expected, actual, "六类场景配比不符合 3.3 节设计");
    }

    /** 全局最小间隔节流（单线程测试语义下足够，不需要更复杂的限流器）。 */
    static final class LlmRequestThrottle {

        private final long minIntervalMillis;
        private final Object lock = new Object();
        private long nextSlotAt = System.currentTimeMillis();

        LlmRequestThrottle(long minIntervalMillis) {
            this.minIntervalMillis = minIntervalMillis;
        }

        void awaitSlot() {
            long sleepMillis;
            synchronized (lock) {
                long now = System.currentTimeMillis();
                if (nextSlotAt < now) {
                    nextSlotAt = now;
                }
                sleepMillis = nextSlotAt - now;
                nextSlotAt += minIntervalMillis;
            }
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("throttle-interrupted", interrupted);
                }
            }
        }
    }

    /**
     * 故障感知装饰器：Runner 在每轮请求前设置静态 FaultMode（与 CI 完全同一开关），
     * 这里对真实模型对称注入：FAILURE 抛异常、TIMEOUT 挂起到超过 Guard 超时、
     * NORMAL 节流后透传真实调用。两侧被测方共享本 bean，注入条件完全一致。
     */
    static final class FaultAwareChatModel implements ChatLanguageModel {

        private final ChatLanguageModel delegate;
        private final long timeoutSleepMillis;

        FaultAwareChatModel(ChatLanguageModel delegate, long timeoutSleepMillis) {
            this.delegate = delegate;
            this.timeoutSleepMillis = timeoutSleepMillis;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            return guardedCall(() -> delegate.generate(messages));
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages,
                                            List<ToolSpecification> toolSpecifications) {
            return guardedCall(() -> delegate.generate(messages, toolSpecifications));
        }

        private Response<AiMessage> guardedCall(java.util.function.Supplier<Response<AiMessage>> call) {
            switch (ScriptedFaultChatModel.currentMode()) {
                case FAILURE -> throw new IllegalStateException("injected-llm-failure");
                case TIMEOUT -> {
                    try {
                        Thread.sleep(timeoutSleepMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    // Guard 已超时放弃，这里只是兜底返回。
                    return Response.from(AiMessage.from("injected-timeout-fallback"));
                }
                default -> {
                    THROTTLE.awaitSlot();
                    return call.get();
                }
            }
        }
    }

    /** Judge 专用：节流 + 对 429/网络异常做指数退避重试（Judge 不在 Guard 管控内，重试安全）。 */
    static final class ThrottledRetryChatModel implements ChatLanguageModel {

        private static final long[] BACKOFF_MILLIS = {20_000, 40_000};

        private final ChatLanguageModel delegate;

        ThrottledRetryChatModel(ChatLanguageModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            return callWithRetry(() -> delegate.generate(messages));
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages,
                                            List<ToolSpecification> toolSpecifications) {
            return callWithRetry(() -> delegate.generate(messages, toolSpecifications));
        }

        private Response<AiMessage> callWithRetry(java.util.function.Supplier<Response<AiMessage>> call) {
            RuntimeException last = null;
            for (int attempt = 0; attempt <= BACKOFF_MILLIS.length; attempt++) {
                if (attempt > 0) {
                    sleepQuietly(BACKOFF_MILLIS[attempt - 1]);
                }
                try {
                    THROTTLE.awaitSlot();
                    return call.get();
                } catch (RuntimeException failure) {
                    last = failure;
                }
            }
            throw last;
        }

        private void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("judge-retry-interrupted", interrupted);
            }
        }
    }

    /** 断点续跑存储：一行一个执行（端 × 场景 × repeat），append-only，重跑时按 key 跳过。 */
    static final class RealRunStore {

        private final Path file;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

        private RealRunStore(Path file) {
            this.file = file;
        }

        static RealRunStore load(Path file) throws IOException {
            RealRunStore store = new RealRunStore(file);
            if (!Files.exists(file)) {
                return store;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = store.objectMapper.readValue(line,
                        store.objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class,
                                String.class, Object.class));
                store.rows.put(keyOf(row), row);
            }
            return store;
        }

        private static String keyOf(Map<String, Object> row) {
            return row.get("side") + "|" + row.get("id") + "|" + row.get("repeat");
        }

        boolean has(EndpointSide side, String scenarioId, int repeat) {
            return rows.containsKey(side.name() + "|" + scenarioId + "|" + repeat);
        }

        synchronized void append(EndpointSide side, ScenarioRunResult run,
                                 ScenarioJudgement judgement) throws IOException {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", run.scenario().getId());
            row.put("category", run.scenario().category);
            row.put("side", side.name());
            row.put("agent", side.blindLabel());
            row.put("repeat", run.repeatOf());
            row.put("passed", run.passed());
            row.put("judgeTaskCompleted", judgement.taskCompleted());
            row.put("judgeScore", judgement.score());
            row.put("judgeRationale", judgement.rationale());
            row.put("judgeName", judgement.judgeName());
            row.put("violations", run.allViolations());
            row.put("turns", run.toReportRow().get("turns"));
            Files.createDirectories(file.getParent());
            Files.writeString(file, objectMapper.writeValueAsString(row) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            rows.put(keyOf(row), row);
        }

        List<Map<String, Object>> rows() {
            return new ArrayList<>(rows.values());
        }
    }

    /** 真实跑批报告：场景级聚合 + 六类分项 + 汇总，落盘格式与 M3 报告同构。 */
    private static final class RealReport {

        private final List<Map<String, Object>> caseRows = new ArrayList<>();
        private final Map<String, Object> summary = new LinkedHashMap<>();

        static RealReport build(List<Scenario> scenarios, List<Map<String, Object>> runRows) {
            RealReport report = new RealReport();
            for (Scenario scenario : scenarios) {
                for (EndpointSide side : List.of(EndpointSide.WORKFLOW, EndpointSide.BASELINE)) {
                    List<Map<String, Object>> runs = runRows.stream()
                            .filter(row -> scenario.getId().equals(row.get("id"))
                                    && side.name().equals(row.get("side")))
                            .sorted(Comparator.comparingInt(row -> (Integer) row.get("repeat")))
                            .toList();
                    if (runs.isEmpty()) {
                        continue;
                    }
                    int passed = (int) runs.stream().filter(row -> (Boolean) row.get("passed")).count();
                    int judgeCompleted = (int) runs.stream()
                            .filter(row -> (Boolean) row.get("judgeTaskCompleted")).count();
                    double scoreSum = runs.stream()
                            .mapToDouble(row -> ((Number) row.get("judgeScore")).doubleValue()).sum();
                    List<String> violations = new ArrayList<>();
                    runs.forEach(run -> ((List<?>) run.get("violations")).forEach(violation ->
                            violations.add("r" + run.get("repeat") + ": " + violation)));

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", scenario.getId());
                    row.put("category", scenario.category);
                    row.put("agent", side.blindLabel());
                    row.put("runs", runs.size());
                    row.put("passedRuns", passed);
                    row.put("majorityPassed", passed * 2 > runs.size());
                    row.put("judgeCompletedRuns", judgeCompleted);
                    row.put("majorityTaskCompleted", judgeCompleted * 2 > runs.size());
                    row.put("avgJudgeScore", runs.isEmpty() ? 0 : scoreSum / runs.size());
                    row.put("violations", violations);
                    row.put("runDetails", runs);
                    report.caseRows.add(row);
                }
            }
            report.buildSummary(runRows);
            return report;
        }

        private void buildSummary(List<Map<String, Object>> runRows) {
            Map<String, Object> perCategory = new LinkedHashMap<>();
            for (String category : CATEGORY_ORDER) {
                Map<String, Object> categoryRow = new LinkedHashMap<>();
                for (EndpointSide side : List.of(EndpointSide.WORKFLOW, EndpointSide.BASELINE)) {
                    categoryRow.put(side.blindLabel(), agentAggregate(runRows, category, side));
                }
                perCategory.put(category, categoryRow);
            }
            summary.put("perCategory", perCategory);
            Map<String, Object> agents = new LinkedHashMap<>();
            for (EndpointSide side : List.of(EndpointSide.WORKFLOW, EndpointSide.BASELINE)) {
                agents.put(side.blindLabel(), agentAggregate(runRows, null, side));
            }
            summary.put("agents", agents);
            summary.put("majorityVoteNote", "ADVERSARIAL/HALLUCINATION 场景 repeat=3，passes > runs/2 判过");
            summary.put("disclosure", Map.of("Agent A", "workflow", "Agent B", "baseline"));
        }

        private Map<String, Object> agentAggregate(List<Map<String, Object>> runRows,
                                                   String category,
                                                   EndpointSide side) {
            List<Map<String, Object>> rows = runRows.stream()
                    .filter(row -> side.name().equals(row.get("side")))
                    .filter(row -> category == null || category.equals(row.get("category")))
                    .toList();
            int passed = (int) rows.stream().filter(row -> (Boolean) row.get("passed")).count();
            int judgeCompleted = (int) rows.stream()
                    .filter(row -> (Boolean) row.get("judgeTaskCompleted")).count();
            double scoreAvg = rows.stream()
                    .mapToDouble(row -> ((Number) row.get("judgeScore")).doubleValue()).average()
                    .orElse(0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("runs", rows.size());
            item.put("passedRuns", passed);
            item.put("passRate", EvalReportWriter.rate(passed, rows.size()));
            item.put("judgeTaskCompletedRuns", judgeCompleted);
            item.put("judgeTaskCompletedRate", EvalReportWriter.rate(judgeCompleted, rows.size()));
            item.put("avgJudgeScore", Math.round(scoreAvg * 100) / 100.0);
            return item;
        }

        void writeTo(String reportName, String modelName) throws IOException {
            Map<String, Object> summaryOut = new LinkedHashMap<>(summary);
            summaryOut.put("llmMode", "real:" + modelName);
            summaryOut.put("judge", LlmBlindJudge.JUDGE_NAME + " (temperature 0.0, 同一模型)");
            summaryOut.put("throttle", THROTTLE.minIntervalMillis + "ms 全局最小间隔");
            List<Map<String, Object>> sortedRows = new ArrayList<>(caseRows);
            sortedRows.sort(Comparator
                    .comparing((Map<String, Object> row) -> String.valueOf(row.get("id")))
                    .thenComparing(row -> String.valueOf(row.get("agent"))));
            EvalReportWriter.write(reportName, summaryOut, sortedRows);
        }

        String toText(int freshRuns) {
            StringBuilder text = new StringBuilder("\n===== M4 真实模型对照报告（stdout 摘要）=====\n");
            text.append("本次新执行 ").append(freshRuns).append(" 条（其余来自 checkpoint）\n");
            text.append("场景报告已写入 target/eval-reports/scenario-comparison-real.json（冒烟为 -smoke）\n\n");
            for (String category : CATEGORY_ORDER) {
                text.append(String.format("%-13s", category));
                Map<?, ?> categoryRow = (Map<?, ?>) ((Map<?, ?>) summary.get("perCategory")).get(category);
                for (String label : List.of("Agent A", "Agent B")) {
                    Map<?, ?> agent = (Map<?, ?>) categoryRow.get(label);
                    int runs = (Integer) agent.get("runs");
                    int passed = (Integer) agent.get("passedRuns");
                    int completed = (Integer) agent.get("judgeTaskCompletedRuns");
                    double passRate = (Double) agent.get("passRate") * 100;
                    double completedRate = (Double) agent.get("judgeTaskCompletedRate") * 100;
                    double avgScore = (Double) agent.get("avgJudgeScore");
                    text.append(String.format("| %s 断言 %.0f%%(%d/%d) 语义完成 %.0f%%(%d/%d) 均分 %.2f  ",
                            label, passRate, passed, runs, completedRate, completed, runs, avgScore));
                }
                text.append('\n');
            }
            return text.toString();
        }
    }
}
