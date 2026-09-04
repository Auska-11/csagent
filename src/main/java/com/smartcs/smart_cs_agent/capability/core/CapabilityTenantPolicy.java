package com.smartcs.smart_cs_agent.capability.core;

import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 租户维度的能力面配置（渠道能力面）。
 *
 * <p>同一个 Capability 目录对所有租户可见，但每个租户（渠道）可以声明自己的屏蔽名单，
 * 由 CapabilityGateway 在 Provider 执行前统一拦截。例如闲鱼渠道的买家在真实交易平台上
 * 说“确认退货”，却把申请写进演示库——语义上无法向买家交代，因此 {@code return.apply}
 * 对 {@code xianyu} 租户关闭（docs-76 §5.2 设计点 3）。</p>
 *
 * <p>{@code handoff.request} 刻意不在屏蔽名单里：它是双层转人工的业务层出口，
 * 被拦截的话闲鱼买家永远无法触发“暂停自动回复”标志位（docs-76 §5.2 设计点 4）。</p>
 *
 * <p>本类只做执行期拦截；MCP Catalog 和调试页仍展示完整目录，是否开放由调用时的租户决定。</p>
 */
@Component
@ConfigurationProperties(prefix = "smartcs.capability")
public class CapabilityTenantPolicy {

    /** tenantId -> 该租户的能力屏蔽规则。键比较不区分大小写。 */
    private Map<String, TenantRules> tenantPolicies = new LinkedHashMap<>();

    public Map<String, TenantRules> getTenantPolicies() {
        return tenantPolicies;
    }

    public void setTenantPolicies(Map<String, TenantRules> tenantPolicies) {
        this.tenantPolicies = tenantPolicies == null ? new LinkedHashMap<>() : tenantPolicies;
    }

    /** 旧工具名（如 return_apply）与 canonical ID（return.apply）都按屏蔽名单判断。 */
    public boolean isBlocked(String tenantId, String capabilityId) {
        if (tenantId == null || tenantId.isBlank() || capabilityId == null || capabilityId.isBlank()) {
            return false;
        }
        TenantRules rules = tenantPolicies.get(tenantId.trim().toLowerCase(Locale.ROOT));
        return rules != null && rules.isBlocked(CapabilityIds.canonicalize(capabilityId));
    }

    /** 测试用：不屏蔽任何能力的空策略。 */
    public static CapabilityTenantPolicy permissive() {
        return new CapabilityTenantPolicy();
    }

    /** 单个租户的屏蔽名单。ID 比较不区分大小写。 */
    public static class TenantRules {

        private java.util.Set<String> blockedCapabilities = new java.util.LinkedHashSet<>();

        public java.util.Set<String> getBlockedCapabilities() {
            return blockedCapabilities;
        }

        public void setBlockedCapabilities(java.util.Set<String> blockedCapabilities) {
            this.blockedCapabilities = blockedCapabilities == null
                    ? new java.util.LinkedHashSet<>()
                    : blockedCapabilities;
        }

        boolean isBlocked(String canonicalCapabilityId) {
            for (String blocked : blockedCapabilities) {
                if (blocked != null && blocked.trim().equalsIgnoreCase(canonicalCapabilityId)) {
                    return true;
                }
            }
            return false;
        }
    }
}
