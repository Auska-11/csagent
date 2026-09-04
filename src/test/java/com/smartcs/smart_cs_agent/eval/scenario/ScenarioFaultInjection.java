package com.smartcs.smart_cs_agent.eval.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 单轮可控故障注入。
 *
 * <p>M1 支持 LLM 通道的两种故障；能力结果未知、Qdrant 不可用等按 3.3 节规划在后续里程碑补。
 * 故障由测试内的 {@link ScriptedFaultChatModel} 解释执行，真实模型跑批（M4）时这些开关
 * 由 OpenRouter 侧的真实失败率自然替代，场景文件本身不用改。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScenarioFaultInjection {

    /** LLM 通道故障：none（默认）| failure（模型抛异常）| timeout（模型挂起直到调用超时）。 */
    public String llm = "none";

    public String getLlm() {
        return llm;
    }

    public boolean hasLlmFailure() {
        return "failure".equalsIgnoreCase(llm);
    }

    public boolean hasLlmTimeout() {
        return "timeout".equalsIgnoreCase(llm);
    }

    public boolean isNone() {
        return !hasLlmFailure() && !hasLlmTimeout();
    }
}
