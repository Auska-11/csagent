package com.smartcs.smart_cs_agent.tools;

/**
 * 结构化工具输出。
 *
 * 【为什么需要这个小类？】
 * 工具执行后通常有两种结果：
 * - message：给 LLM 看的文本，适合生成自然语言客服回复
 * - data：给程序看的结构化数据，适合 Trace、评测、监控和后续 MCP 响应
 *
 * 之前很多工具只有 String 返回值，LLM 能看懂，但程序不好稳定判断。
 * 这个类把两类结果放在一起，让工具层同时服务“模型”和“工程系统”。
 */
public class StructuredToolOutput {

    /** 给 LLM 或用户话术生成使用的文本 */
    private final String message;

    /** 给程序读取的结构化数据，通常是 Map 或 List */
    private final Object data;

    public StructuredToolOutput(String message, Object data) {
        this.message = message;
        this.data = data;
    }

    public String getMessage() {
        return message;
    }

    public Object getData() {
        return data;
    }
}
