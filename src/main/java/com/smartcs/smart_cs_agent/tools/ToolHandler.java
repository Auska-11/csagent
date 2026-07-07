package com.smartcs.smart_cs_agent.tools;

/**
 * 工具执行函数。
 *
 * ToolRegistry 注册工具时，会把工具定义和执行函数绑定起来。
 * 这样 Runtime 不需要知道具体调用 OrderTools 还是 ReturnTools，只需要执行 ToolCall。
 */
@FunctionalInterface
public interface ToolHandler {

    ToolResult execute(ToolCall call);
}
