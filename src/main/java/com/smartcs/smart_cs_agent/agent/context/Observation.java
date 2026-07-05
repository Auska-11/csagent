package com.smartcs.smart_cs_agent.agent.context;

/**
 * 观察结果
 *
 * 【什么是 Observation？】
 * Observation 记录了 Agent 执行过程中的中间结果。
 * 比如：工具调用的结果、LLM 的回复等。
 *
 * 【使用场景】
 * 1. 工具调用后：记录工具返回的结果
 * 2. LLM 调用后：记录 LLM 的回复
 * 3. Skill 可以读取这些结果，决定下一步
 *
 * 【字段说明】
 * - type：观察类型，如 capability, llm_response
 * - data：观察数据，可以是任何类型
 */
public class Observation {

    /** 观察类型：capability（工具调用）、llm_response（LLM 回复）等 */
    private final String type;

    /** 观察数据：可以是任何类型 */
    private final Object data;

    /**
     * 构造函数
     *
     * @param type 观察类型
     * @param data 观察数据
     */
    public Observation(String type, Object data) {
        this.type = type;
        this.data = data;
    }

    /** 获取观察类型 */
    public String getType() {
        return type;
    }

    /** 获取观察数据 */
    public Object getData() {
        return data;
    }

    @Override
    public String toString() {
        return "Observation{" +
                "type=" + type +
                ", data=" + (data != null ? data.toString().substring(0, Math.min(50, data.toString().length())) + "..." : "null") +
                "}";
    }
}