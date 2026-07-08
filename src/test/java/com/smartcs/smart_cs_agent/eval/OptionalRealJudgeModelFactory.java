package com.smartcs.smart_cs_agent.eval;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * 可选真实 Judge 模型工厂。
 *
 * 默认的 LLM-as-Judge 测试使用 Fake Judge，保证本地和 CI 不依赖网络、API Key 和真实模型稳定性。
 * 当我们想做面试演示或手动评测真实模型时，可以通过 JVM 参数打开真实 Judge：
 *
 * mvn "-Dsmartcs.eval.real-judge.enabled=true" "-Dtest=LlmAsJudgeEvaluationTest" test
 *
 * 这个类只放在 test 目录下，是因为“真实 Judge 评测”属于离线评测能力，
 * 不是线上客服接口运行时必须依赖的业务功能。
 */
class OptionalRealJudgeModelFactory {

    static final String ENABLED_PROPERTY = "smartcs.eval.real-judge.enabled";
    static final String ASSERT_EXPECTATION_PROPERTY = "smartcs.eval.real-judge.assert-expectation";
    static final String API_KEY_PROPERTY = "smartcs.eval.real-judge.api-key";
    static final String BASE_URL_PROPERTY = "smartcs.eval.real-judge.base-url";
    static final String MODEL_NAME_PROPERTY = "smartcs.eval.real-judge.model-name";

    private OptionalRealJudgeModelFactory() {
    }

    static boolean enabled() {
        return booleanValue(ENABLED_PROPERTY, "SMARTCS_EVAL_REAL_JUDGE_ENABLED", false);
    }

    static boolean shouldAssertExpectation() {
        /*
         * 真实模型输出具有一定随机性。默认打开真实 Judge 时只生成报告，不强制让测试失败；
         * 如果想把它作为严格质量门禁，可以显式传入：
         * -Dsmartcs.eval.real-judge.assert-expectation=true
         */
        return booleanValue(ASSERT_EXPECTATION_PROPERTY, "SMARTCS_EVAL_REAL_JUDGE_ASSERT_EXPECTATION", false);
    }

    static ChatLanguageModel create() {
        String apiKey = requiredConfig(
                API_KEY_PROPERTY,
                "SMARTCS_EVAL_REAL_JUDGE_API_KEY",
                "OPENAI_API_KEY",
                "真实 Judge 已开启，但没有配置 API Key"
        );
        String baseUrl = configValue(
                BASE_URL_PROPERTY,
                "SMARTCS_EVAL_REAL_JUDGE_BASE_URL",
                "OPENAI_BASE_URL",
                "https://api.openai.com/v1"
        );
        String modelName = requiredConfig(
                MODEL_NAME_PROPERTY,
                "SMARTCS_EVAL_REAL_JUDGE_MODEL_NAME",
                "OPENAI_MODEL_NAME",
                "真实 Judge 已开启，但没有配置模型名称"
        );

        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                // Judge 评测更关注稳定判断，所以温度设置为 0，减少随机波动。
                .temperature(0.0)
                .maxTokens(1000)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    static String judgeMode() {
        if (!enabled()) {
            return "fake";
        }
        String modelName = configValue(
                MODEL_NAME_PROPERTY,
                "SMARTCS_EVAL_REAL_JUDGE_MODEL_NAME",
                "OPENAI_MODEL_NAME",
                "unknown"
        );
        return "real:" + modelName;
    }

    private static boolean booleanValue(String propertyName, String envName, boolean defaultValue) {
        String value = configValue(propertyName, envName, null, null);
        if (isBlank(value)) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value.trim())
                || "1".equals(value.trim())
                || "yes".equalsIgnoreCase(value.trim());
    }

    private static String requiredConfig(String propertyName, String envName, String fallbackEnvName, String message) {
        String value = configValue(propertyName, envName, fallbackEnvName, null);
        if (isBlank(value)) {
            throw new IllegalStateException(message + "，请配置 " + propertyName + " 或环境变量 " + envName);
        }
        return value.trim();
    }

    private static String configValue(String propertyName, String envName, String fallbackEnvName, String defaultValue) {
        String propertyValue = System.getProperty(propertyName);
        if (!isBlank(propertyValue)) {
            return propertyValue;
        }

        String envValue = System.getenv(envName);
        if (!isBlank(envValue)) {
            return envValue;
        }

        if (!isBlank(fallbackEnvName)) {
            String fallbackEnvValue = System.getenv(fallbackEnvName);
            if (!isBlank(fallbackEnvValue)) {
                return fallbackEnvValue;
            }
        }

        return defaultValue;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
