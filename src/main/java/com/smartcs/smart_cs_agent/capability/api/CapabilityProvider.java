package com.smartcs.smart_cs_agent.capability.api;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;

import java.util.Collection;

/**
 * Capability 的可插拔实现来源。
 *
 * <p>典型实现：</p>
 * <ul>
 *     <li>Local Provider：直接调用当前 Spring Bean/Domain Service。</li>
 *     <li>MCP Provider：调用远程 MCP Server。</li>
 *     <li>Fake Provider：测试时返回确定结果。</li>
 * </ul>
 *
 * <p>Provider 不决定“何时调用”；这是 Workflow 的职责。Provider 只保证契约内的一次原子调用。</p>
 */
public interface CapabilityProvider {

    /** Provider 的稳定 ID，用于配置、冲突检查和 Trace。 */
    String providerId();

    /** 返回 Provider 可以执行的全部能力定义。 */
    Collection<CapabilityDefinition> definitions();

    /**
     * 执行能力调用。
     *
     * @param call 传输无关的能力请求
     * @param context 服务端执行上下文；身份、traceId 和幂等键不能由 LLM 覆盖
     */
    CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context);
}
