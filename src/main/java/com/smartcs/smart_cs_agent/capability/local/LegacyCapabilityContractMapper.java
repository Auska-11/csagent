package com.smartcs.smart_cs_agent.capability.local;

import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.tools.ToolDefinition;

/**
 * 把旧 ToolDefinition 的 Schema 包装为新版 CapabilityDefinition，并集中补充安全元数据。
 *
 * <p>过渡期 Schema 仍来自 ToolRegistry；版本、身份、风险和超时只在这里维护一份。
 * 逐个迁移为独立 Local Provider 后，对应契约会完全离开旧 ToolRegistry。</p>
 */
public final class LegacyCapabilityContractMapper {

    private LegacyCapabilityContractMapper() {
    }

    public static CapabilityDefinition fromLegacy(ToolDefinition tool) {
        String id = CapabilityIds.canonicalize(tool.getName());
        Policy policy = policyFor(id);
        return new CapabilityDefinition(
                id,
                "1.0.0",
                tool.getDescription(),
                tool.getInputSchema(),
                tool.getOutputSchema(),
                policy.requiresIdentity,
                policy.requiresAuthentication,
                policy.sideEffect,
                policy.idempotent,
                policy.timeoutMs,
                policy.riskLevel
        );
    }

    private static Policy policyFor(String id) {
        return switch (id) {
            case CapabilityIds.ORDER_LOOKUP,
                 CapabilityIds.ORDER_LIST_BY_USER,
                 CapabilityIds.RETURN_LOOKUP,
                 CapabilityIds.USER_LOOKUP -> Policy.identityRead();
            case CapabilityIds.HANDOFF_REQUEST -> Policy.sideEffect(false, CapabilityRiskLevel.MEDIUM);
            case CapabilityIds.RETURN_APPLY -> Policy.sideEffect(true, CapabilityRiskLevel.HIGH);
            default -> Policy.publicRead();
        };
    }

    private record Policy(boolean requiresIdentity,
                          boolean requiresAuthentication,
                          boolean sideEffect,
                          boolean idempotent,
                          long timeoutMs,
                          CapabilityRiskLevel riskLevel) {

        private static Policy publicRead() {
            return new Policy(false, false, false, false, 10_000, CapabilityRiskLevel.READ_ONLY);
        }

        private static Policy identityRead() {
            return new Policy(true, false, false, false, 10_000, CapabilityRiskLevel.READ_ONLY);
        }

        private static Policy sideEffect(boolean requiresAuthentication, CapabilityRiskLevel risk) {
            return new Policy(true, requiresAuthentication, true, true, 30_000, risk);
        }
    }
}
