package com.smartcs.smart_cs_agent.security;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 应用日志安全默认值测试。
 *
 * <p>直接解析生产 application.yml，防止以后升级 LangChain4j 或复制配置时，误把完整 Prompt 和
 * 模型回复日志重新打开。测试只检查默认值；部署环境也不应覆盖为 true。</p>
 */
class ApplicationLogSafetyTest {

    @Test
    void shouldDisableFullModelRequestAndResponseLogsByDefault() {
        InputStream input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("application.yml");
        assertNotNull(input, "application.yml should be available on the test classpath");

        Map<String, Object> root = new Yaml().load(input);
        Map<String, Object> chatModel = nestedMap(
                nestedMap(
                        nestedMap(root, "langchain4j"),
                        "open-ai"
                ),
                "chat-model"
        );

        assertEquals(Boolean.FALSE, chatModel.get("log-requests"));
        assertEquals(Boolean.FALSE, chatModel.get("log-responses"));
    }

    @Test
    void shouldKeepModelRetriesAndAllAgentDeadlinesBoundedByDefault() {
        InputStream input = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("application.yml");
        assertNotNull(input, "application.yml should be available on the test classpath");

        Map<String, Object> root = new Yaml().load(input);
        Map<String, Object> smartcs = nestedMap(root, "smartcs");
        Map<String, Object> agent = nestedMap(smartcs, "agent");
        Map<String, Object> llmExecution = nestedMap(nestedMap(smartcs, "llm"), "execution");
        Map<String, Object> chatModel = nestedMap(
                nestedMap(nestedMap(root, "langchain4j"), "open-ai"),
                "chat-model"
        );

        assertEquals("${SMARTCS_AGENT_TURN_TIMEOUT_MS:30000}", agent.get("turn-timeout-ms"));
        assertEquals("${SMARTCS_LLM_EXECUTION_CALL_TIMEOUT_MS:12000}", llmExecution.get("call-timeout-ms"));
        assertEquals("${OPENAI_TIMEOUT:PT10S}", chatModel.get("timeout"));
        assertEquals("${OPENAI_MAX_RETRIES:1}", chatModel.get("max-retries"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new AssertionError("Missing YAML object: " + key);
        }
        return (Map<String, Object>) value;
    }
}
