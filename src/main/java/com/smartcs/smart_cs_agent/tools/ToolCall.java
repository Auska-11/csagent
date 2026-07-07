package com.smartcs.smart_cs_agent.tools;

import java.util.Collections;
import java.util.Map;

/**
 * 一次工具调用请求。
 *
 * 【和 CapabilityAction 的关系】
 * CapabilityAction 是 Agent 决策层的 Action，表示“我想调用某个能力”。
 * ToolCall 是工具层真正执行时使用的标准请求，表示“调用哪个工具、传什么参数”。
 *
 * 现在两者字段很像，但职责不同：
 * - CapabilityAction 属于 Agent Action 抽象
 * - ToolCall 属于工具协议抽象
 *
 * 后续如果升级成真正 MCP Client，ToolCall 就可以很自然地转换成 MCP 请求。
 */
public class ToolCall {

    /** 工具名称，例如 order_lookup */
    private final String name;

    /** 工具参数，例如 {order_id: "ORD-001"} */
    private final Map<String, Object> arguments;

    public ToolCall(String name, Map<String, Object> arguments) {
        this.name = name;
        this.arguments = arguments == null ? Collections.emptyMap() : Map.copyOf(arguments);
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
