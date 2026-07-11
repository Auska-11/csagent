package com.smartcs.smart_cs_agent.agent.action;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 要求用户明确确认有副作用操作的 Action。
 *
 * <p>AskUserAction 只表示“缺少信息”；ConfirmAction 表示“信息已经完整，但执行会改变业务状态”。
 * 把两者分开后，Runtime、Trace 和未来 Guardrail 可以明确识别确认边界。</p>
 */
public final class ConfirmAction implements AgentAction {

    /** 给用户展示的确认问题。 */
    private final String question;

    /** 稳定操作类型，例如 return_apply，后续可用于生成幂等键和审计。 */
    private final String operation;

    /** 即将提交的只读摘要，不包含由 LLM 自由生成的隐藏参数。 */
    private final Map<String, Object> summary;

    public ConfirmAction(String question, String operation, Map<String, Object> summary) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("confirmation question cannot be empty");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("confirmation operation cannot be empty");
        }
        this.question = question;
        this.operation = operation;
        this.summary = summary == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(summary));
    }

    public String getQuestion() {
        return question;
    }

    public String getOperation() {
        return operation;
    }

    public Map<String, Object> getSummary() {
        return summary;
    }
}
