package com.smartcs.smart_cs_agent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * LLM-as-Judge 评测器。
 *
 * 【第一版做什么？】
 * 1. 根据用例构造 Judge Prompt。
 * 2. 调用 Judge 模型。
 * 3. 把模型返回的 JSON 解析成 LlmJudgeResult。
 *
 * 当前测试会注入 FakeChatLanguageModel，不依赖真实模型。
 * 真实使用时只需要把 judgeModel 换成真正的 ChatLanguageModel。
 */
class LlmJudgeEvaluator {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChatLanguageModel judgeModel;
    private final ObjectMapper objectMapper;

    LlmJudgeEvaluator(ChatLanguageModel judgeModel) {
        this(judgeModel, new ObjectMapper());
    }

    LlmJudgeEvaluator(ChatLanguageModel judgeModel, ObjectMapper objectMapper) {
        this.judgeModel = judgeModel;
        this.objectMapper = objectMapper;
    }

    LlmJudgeResult judge(LlmJudgeEvalCase evalCase) {
        String prompt = LlmJudgePromptBuilder.build(evalCase);
        Response<AiMessage> response = judgeModel.generate(List.<ChatMessage>of(UserMessage.from(prompt)));
        return parseResult(response.content().text());
    }

    LlmJudgeResult parseResult(String rawText) {
        String json = extractJson(rawText);
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(json, MAP_TYPE);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Judge 返回内容不是合法 JSON：" + rawText, exception);
        }

        int accuracyScore = intValue(payload.get("accuracyScore"));
        int completenessScore = intValue(payload.get("completenessScore"));
        int toneScore = intValue(payload.get("toneScore"));
        int safetyScore = intValue(payload.get("safetyScore"));
        double overallScore = doubleValue(payload.get("overallScore"));
        boolean passed = booleanValue(payload.get("passed"));
        String reason = stringValue(payload.get("reason"));
        List<String> issues = listOfString(payload.get("issues"));

        return new LlmJudgeResult(
                accuracyScore,
                completenessScore,
                toneScore,
                safetyScore,
                overallScore,
                passed,
                reason,
                issues
        );
    }

    /**
     * 有些模型可能会在 JSON 前后包一点说明文字。
     * 第一版做一个轻量兜底：截取第一个 { 到最后一个 }。
     */
    private String extractJson(String rawText) {
        if (rawText == null) {
            throw new IllegalArgumentException("Judge 返回内容为空");
        }

        int start = rawText.indexOf('{');
        int end = rawText.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Judge 返回内容中没有 JSON 对象：" + rawText);
        }
        return rawText.substring(start, end + 1);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> listOfString(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .toList();
    }
}
