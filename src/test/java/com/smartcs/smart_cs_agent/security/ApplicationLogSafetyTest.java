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

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new AssertionError("Missing YAML object: " + key);
        }
        return (Map<String, Object>) value;
    }
}
