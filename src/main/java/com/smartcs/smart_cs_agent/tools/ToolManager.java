package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityGateway;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.local.ToolRegistryCapabilityProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具管理器。
 *
 * 【当前定位】
 * ToolManager 现在是旧工具 API 到新 CapabilityGateway 的兼容门面（Facade）。
 * Runtime 暂时仍然调用 ToolManager，避免一次性修改全部上层代码；
 * ToolManager 内部已经把请求转换成 CapabilityCall，交给 CapabilityGateway 选择 Provider。
 *
 * 【为什么不直接删掉 ToolManager？】
 * 因为当前 Runtime、测试和文档都已经依赖 ToolManager。
 * 保留它可以让迁移更平滑：
 *
 * Runtime -> ToolManager -> CapabilityGateway -> CapabilityProvider -> 具体能力
 *
 * 后续如果要换成真正 MCP Client，也可以只改 ToolManager 内部。
 */
@Component
public class ToolManager {

    /** 新架构的能力调用唯一入口；当前默认 Provider 会继续委托旧 ToolRegistry。 */
    private final CapabilityGateway capabilityGateway;

    /**
     * 过渡期保留旧注册表，仅用于兼容现有 tools/list 和 Controller 返回类型。
     * 执行调用已经不再直接经过这个字段。
     */
    private final ToolRegistry toolRegistry;

    /**
     * Spring 生产环境使用的构造函数。
     *
     * <p>CapabilityGateway 和 ToolRegistryCapabilityProvider 都由 Spring 自动发现，
     * 因此后续增加 MCP Provider 时不需要修改 ToolManager 的分发代码。</p>
     */
    @Autowired
    public ToolManager(CapabilityGateway capabilityGateway, ToolRegistry toolRegistry) {
        this.capabilityGateway = capabilityGateway;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 兼容旧测试和手动装配代码的构造函数。
     *
     * <p>它会在内存中组装“一个 Gateway + 一个本地旧工具 Provider”。现有测试可以保持原样，
     * 同时测试到的实际执行链已经包含 CapabilityGateway。</p>
     */
    public ToolManager(ToolRegistry toolRegistry) {
        this(
                new DefaultCapabilityGateway(List.of(new ToolRegistryCapabilityProvider(toolRegistry, true))),
                toolRegistry
        );
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
        return execute(toolCall, ExecutionContext.legacy());
    }

    /**
     * 带服务端执行上下文的能力调用入口。
     *
     * <p>后续 AgentRuntime 接入真实认证、traceId 和幂等键后会调用这个重载；旧入口使用显式
     * Legacy 上下文，避免把客户端参数误认为已认证身份。</p>
     */
    public ToolResult execute(ToolCall toolCall, ExecutionContext executionContext) {
        String requestedName = toolCall.getName();
        String canonicalId = CapabilityIds.canonicalize(requestedName);
        CapabilityResult<?> result = capabilityGateway.invoke(
                new CapabilityCall(canonicalId, toolCall.getArguments()),
                executionContext
        );
        return ToolResult.fromCapability(
                result.isSuccess(),
                requestedName,
                result.getMessage(),
                result.getData(),
                legacyErrorCode(result.getErrorCode()),
                result.getOutcome(),
                result.getProviderId(),
                result.isReplayed()
        );
    }

    /**
     * 列出当前系统支持的工具。
     *
     * 后续可以暴露成调试接口，或用于生成 MCP tools/list 响应。
     */
    public List<ToolDefinition> listTools() {
        return toolRegistry.listTools();
    }

    /**
     * 新架构的能力目录。MCP Adapter 和 Skill Loader 后续会逐步改用这个方法，
     * 最终删除对旧 ToolDefinition 返回类型的依赖。
     */
    public List<CapabilityDefinition> listCapabilities() {
        return List.copyOf(capabilityGateway.catalog().list());
    }

    /**
     * 迁移期间维持旧 HTTP/MCP Adapter 已公开的错误码。
     *
     * <p>新架构内部把未知能力称为 CAPABILITY_NOT_FOUND；旧接口和已有调用方认识的是
     * TOOL_NOT_FOUND。兼容翻译集中放在门面中，避免新 Gateway 被迫继续使用旧术语。</p>
     */
    private String legacyErrorCode(String capabilityErrorCode) {
        if ("CAPABILITY_NOT_FOUND".equals(capabilityErrorCode)) {
            return "TOOL_NOT_FOUND";
        }
        return capabilityErrorCode;
    }
}
