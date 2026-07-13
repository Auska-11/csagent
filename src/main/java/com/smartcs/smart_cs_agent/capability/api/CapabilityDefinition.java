package com.smartcs.smart_cs_agent.capability.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个原子业务能力的稳定契约。
 *
 * <p>职责：描述能力 ID、用途、输入和输出 Schema。Workflow、调试页面、MCP Server 和测试最终都
 * 应从同一份 CapabilityDefinition 获取契约，避免各维护一份 Tool Schema。</p>
 *
 * <p>JSON Schema 继续使用 Map 表示，便于直接序列化到 MCP；版本、身份要求、副作用、幂等、
 * 超时和风险级别都由本对象统一声明，Provider、Gateway 与 MCP Adapter 不再各维护一份。</p>
 */
public final class CapabilityDefinition {

    private final String id;
    private final String version;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final Map<String, Object> outputSchema;
    private final boolean requiresIdentity;
    private final boolean requiresAuthentication;
    private final boolean sideEffect;
    private final boolean idempotent;
    /**
     * 单次 Provider 的默认最大等待时间。Gateway 会通过 CapabilityExecutionGuard 真正执行该边界，
     * 并与上游 ExecutionContext deadline 取较早值；它不再只是 MCP Catalog 的展示字段。
     */
    private final long defaultTimeoutMs;
    private final CapabilityRiskLevel riskLevel;

    public CapabilityDefinition(String id,
                                String description,
                                Map<String, Object> inputSchema,
                                Map<String, Object> outputSchema) {
        this(
                id,
                "1.0.0",
                description,
                inputSchema,
                outputSchema,
                false,
                false,
                false,
                false,
                30_000,
                CapabilityRiskLevel.READ_ONLY
        );
    }

    public CapabilityDefinition(String id,
                                String version,
                                String description,
                                Map<String, Object> inputSchema,
                                Map<String, Object> outputSchema,
                                boolean requiresIdentity,
                                boolean requiresAuthentication,
                                boolean sideEffect,
                                boolean idempotent,
                                long defaultTimeoutMs,
                                CapabilityRiskLevel riskLevel) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Capability id cannot be empty");
        }
        this.id = id.trim();
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Capability version cannot be empty");
        }
        if (defaultTimeoutMs <= 0) {
            throw new IllegalArgumentException("Capability defaultTimeoutMs must be positive");
        }
        if (requiresAuthentication && !requiresIdentity) {
            throw new IllegalArgumentException("Authenticated capability must also require identity: " + id);
        }
        if (idempotent && !sideEffect) {
            throw new IllegalArgumentException("Only side-effect capability should declare idempotent execution: " + id);
        }
        this.version = version.trim();
        this.description = description == null ? "" : description;
        this.inputSchema = immutableCopy(inputSchema);
        this.outputSchema = immutableCopy(outputSchema);
        this.requiresIdentity = requiresIdentity;
        this.requiresAuthentication = requiresAuthentication;
        this.sideEffect = sideEffect;
        this.idempotent = idempotent;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.riskLevel = riskLevel == null ? CapabilityRiskLevel.READ_ONLY : riskLevel;
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public Map<String, Object> getOutputSchema() {
        return outputSchema;
    }

    public boolean isRequiresIdentity() {
        return requiresIdentity;
    }

    public boolean isRequiresAuthentication() {
        return requiresAuthentication;
    }

    public boolean isSideEffect() {
        return sideEffect;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public CapabilityRiskLevel getRiskLevel() {
        return riskLevel;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }
}
