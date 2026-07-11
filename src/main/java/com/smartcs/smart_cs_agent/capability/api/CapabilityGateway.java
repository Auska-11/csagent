package com.smartcs.smart_cs_agent.capability.api;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;

/**
 * Agent 内核调用业务能力的唯一入口。
 *
 * <p>Workflow 和 ActionHandler 只依赖本接口，因此 Provider 从本地 ToolRegistry 切换为远程 MCP
 * 时，它们不需要修改。Gateway 负责找到 Provider、处理未知能力和统一技术异常。</p>
 */
public interface CapabilityGateway {

    CapabilityCatalog catalog();

    CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context);
}
