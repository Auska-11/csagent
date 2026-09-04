package com.smartcs.smart_cs_agent.capability.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户能力面策略单元测试（docs-76 §5.2 设计点 3）。
 *
 * <p>约束：xianyu 租户屏蔽 return.apply（canonical 与旧名都命中）；handoff.request
 * 必须放行，否则双层转人工的暂停标志位永不触发（设计点 4）；其他租户不受影响。</p>
 */
class CapabilityTenantPolicyTest {

    @Test
    void shouldBlockReturnApplyForXianyuTenantOnly() {
        CapabilityTenantPolicy policy = policy("xianyu", "return.apply");

        assertTrue(policy.isBlocked("xianyu", "return.apply"));
        // 旧工具名 return_apply 经 canonicalize 后同样命中，避免历史调用点绕过屏蔽。
        assertTrue(policy.isBlocked("xianyu", "return_apply"));
        // 租户键不区分大小写。
        assertTrue(policy.isBlocked("XIANYU", "return.apply"));

        // 未配置屏蔽名单的租户完全不受影响。
        assertFalse(policy.isBlocked("default", "return.apply"));
        assertFalse(policy.isBlocked("tenant-a", "return.apply"));
    }

    @Test
    void shouldNeverBlockHandoffRequestForXianyu() {
        CapabilityTenantPolicy policy = policy("xianyu", "return.apply");

        assertFalse(policy.isBlocked("xianyu", "handoff.request"));
    }

    @Test
    void shouldTreatBlankTenantOrCapabilityAsUnblocked() {
        CapabilityTenantPolicy policy = policy("xianyu", "return.apply");

        assertFalse(policy.isBlocked(null, "return.apply"));
        assertFalse(policy.isBlocked("  ", "return.apply"));
        assertFalse(policy.isBlocked("xianyu", null));
        assertFalse(policy.isBlocked("xianyu", "  "));
    }

    @Test
    void permissivePolicyShouldBlockNothing() {
        CapabilityTenantPolicy policy = CapabilityTenantPolicy.permissive();

        assertFalse(policy.isBlocked("xianyu", "return.apply"));
        assertFalse(policy.isBlocked("any-tenant", "any.capability"));
    }

    private CapabilityTenantPolicy policy(String tenantId, String... blocked) {
        CapabilityTenantPolicy policy = new CapabilityTenantPolicy();
        CapabilityTenantPolicy.TenantRules rules = new CapabilityTenantPolicy.TenantRules();
        rules.setBlockedCapabilities(new LinkedHashSet<>(Set.of(blocked)));
        policy.getTenantPolicies().put(tenantId, rules);
        return policy;
    }
}
