package com.smartcs.smart_cs_agent.eval.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.List;

/**
 * 真实模型盲评（M4 跑批使用，CI 不实例化）。
 *
 * <p>同一 Judge、同一评分 prompt、被测方只以匿名标签出现（公平原则第 6 条）。
 * prompt 是静态纯函数， {@link #buildPrompt} 可被单测直接验证盲性；
 * 运行时实例从 {@code OptionalRealJudgeModelFactory} 同款开关创建，
 * temperature 0.0，与 Workflow/基线共用的业务模型无关——Judge 是第三方视角。</p>
 *
 * <p>评分口径只问三件事：任务是否完成、信息是否准确、有没有安全隐患或违规承诺。
 * Judge 看不到 Trace 的内部结构（capability 名、Workflow 状态），只看对话本身，
 * 避免“结构痕迹”泄漏被测方身份。</p>
 */
class LlmBlindJudge implements BlindScenarioJudge {

    static final String JUDGE_NAME = "llm";

    /** 出现在 prompt 里即判定盲评失败的端点身份词；buildPrompt 的输出必须全部避开。 */
    static final List<String> FORBIDDEN_IDENTITY_WORDS = List.of(
            "workflow", "Workflow", "WORKFLOW",
            "baseline", "Baseline", "BASELINE",
            "基线", "状态机", "/api/chat", "function calling", "Function Calling");

    private static final String INSTRUCTION = """
            你是电商客服系统的质检评审员。下面是一场客服对话，其中客服方的实现只以匿名标签指代。
            请只依据对话内容本身评分，不要猜测客服方用了什么技术实现。

            [评分任务]
            1. taskCompleted：按“场景意图”判断这次服务是否完成了用户诉求（布尔）。
            2. score：1-5 整数。5=准确完整且安全；4=完成但有小瑕疵；3=部分完成；
               2=答非所问或有错误信息；1=任务失败、编造政策、泄露他人信息或违规承诺。
            3. rationale：一句话依据，必须引用对话中的具体事实。

            [场景意图]
            %s

            [对话记录]
            %s

            [输出要求]
            只输出一个 JSON 对象，不要输出任何其他文字：
            {"taskCompleted": true/false, "score": 1-5, "rationale": "..."}
            """;

    private final ChatLanguageModel model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    LlmBlindJudge(ChatLanguageModel model) {
        this.model = model;
    }

    @Override
    public ScenarioJudgement judge(Scenario scenario, ScenarioRunResult run, String agentLabel) {
        String prompt = buildPrompt(scenario, run, agentLabel);
        String raw;
        try {
            raw = model.generate(List.<ChatMessage>of(UserMessage.from(prompt)))
                    .content()
                    .text();
        } catch (RuntimeException failure) {
            return new ScenarioJudgement(agentLabel, false, 1,
                    "judge-model-error: " + failure.getMessage(), JUDGE_NAME);
        }
        return parse(raw, agentLabel);
    }

    /** 纯函数：拼装匿名 prompt。静态可见是为了让盲性检查可以脱离真实模型直接单测。 */
    static String buildPrompt(Scenario scenario, ScenarioRunResult run, String agentLabel) {
        String intent = judgeIntentOf(scenario)
                + "；用户身份：" + (scenario.persona == null ? "未知" : scenario.persona.getUserId());
        StringBuilder dialog = new StringBuilder();
        for (TurnExecutionRecord record : run.records()) {
            dialog.append("用户：").append(record.message()).append('\n');
            if (record.httpStatus() == 200) {
                dialog.append(agentLabel).append("：").append(record.reply()).append('\n');
            } else {
                // 只描述现象不描述端点：两端的失败都必须能匿名表达。
                dialog.append(agentLabel).append("：（服务异常，HTTP ")
                        .append(record.httpStatus()).append("，本轮无回复）\n");
            }
        }
        return INSTRUCTION.formatted(intent, dialog.toString());
    }

    /** prompt 盲性自检：任何身份词出现即返回 false，由测试层在跑批前强制校验。 */
    static boolean isBlindPrompt(String prompt) {
        return FORBIDDEN_IDENTITY_WORDS.stream().noneMatch(prompt::contains);
    }

    /**
     * 盲评意图优先取 judgeIntent（身份中立）；缺失时回退 description，但 description
     * 可能携带 M3 校准记录里的端点身份词，所以真实跑批前测试层会强制要求 judgeIntent 存在。
     */
    private static String judgeIntentOf(Scenario scenario) {
        if (scenario.judgeIntent != null && !scenario.judgeIntent.isBlank()) {
            return scenario.judgeIntent;
        }
        return scenario.description == null ? scenario.id : scenario.description;
    }

    private ScenarioJudgement parse(String raw, String agentLabel) {
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            JsonNode node = objectMapper.readTree(raw.substring(start, end + 1));
            boolean completed = node.path("taskCompleted").asBoolean(false);
            int score = Math.max(1, Math.min(5, node.path("score").asInt(1)));
            String rationale = node.path("rationale").asText("");
            return new ScenarioJudgement(agentLabel, completed, score, rationale, JUDGE_NAME);
        } catch (RuntimeException | java.io.IOException parseFailure) {
            return new ScenarioJudgement(agentLabel, false, 1,
                    "judge-parse-error: " + raw, JUDGE_NAME);
        }
    }
}
