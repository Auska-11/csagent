package com.smartcs.smart_cs_agent.tools;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具管理器。
 *
 * 【当前定位】
 * ToolManager 现在是工具层门面（Facade）。
 * Runtime 仍然调用 ToolManager，避免上层代码改动太大；
 * ToolManager 内部再把请求转换成标准 ToolCall，交给 ToolRegistry 执行。
 *
 * 【为什么不直接删掉 ToolManager？】
 * 因为当前 Runtime、测试和文档都已经依赖 ToolManager。
 * 保留它可以让迁移更平滑：
 *
 * Runtime -> ToolManager -> ToolRegistry -> 具体 Tools
 *
 * 后续如果要换成真正 MCP Client，也可以只改 ToolManager 内部。
 */
@Component
public class ToolManager {

    private final ToolRegistry toolRegistry;

    public ToolManager(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 兼容旧调用方式。
     *
     * AgentRuntime 当前拿到的是 CapabilityAction，其中字段叫 capability。
     * 这里把 capability + arguments 转成标准 ToolCall。
     */
    public ToolResult execute(String capability, Map<String, Object> arguments) {
        return execute(new ToolCall(capability, arguments));
    }

    /**
     * 标准工具调用入口。
     */
    public ToolResult execute(ToolCall toolCall) {
        return toolRegistry.execute(toolCall);
    }

    /**
     * 列出当前系统支持的工具。
     *
     * 后续可以暴露成调试接口，或用于生成 MCP tools/list 响应。
     */
    public List<ToolDefinition> listTools() {
        return toolRegistry.listTools();
    }
}
