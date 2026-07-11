package com.smartcs.smart_cs_agent.capability.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Workflow 请求调用某项 Capability 时产生的传输无关请求。
 *
 * <p>它只表达“调用什么能力、业务参数是什么”，不包含 MCP URL、HTTP Header 或本地 Bean 名称。
 * 这些实现细节由 CapabilityProvider 处理。</p>
 */
public final class CapabilityCall {

    private final String capabilityId;
    private final Map<String, Object> arguments;

    public CapabilityCall(String capabilityId, Map<String, Object> arguments) {
        if (capabilityId == null || capabilityId.isBlank()) {
            throw new IllegalArgumentException("Capability id cannot be empty");
        }
        this.capabilityId = capabilityId.trim();
        this.arguments = arguments == null || arguments.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(arguments));
    }

    public String getCapabilityId() {
        return capabilityId;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
