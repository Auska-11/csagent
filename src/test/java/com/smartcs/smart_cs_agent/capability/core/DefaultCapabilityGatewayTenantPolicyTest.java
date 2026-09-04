package com.smartcs.smart_cs_agent.capability.core;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.idempotency.memory.InMemoryCapabilityIdempotencyStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gateway 租户能力面拦截测试（X4，docs-76 §5.2 设计点 3）。
 *
 * <p>拦截必须发生在 Provider 执行之前：被屏蔽的能力不能占用幂等占位，
 * 更不能启动 Provider。业务拒绝（BUSINESS_REJECTED）让 Workflow 能用正常话术收尾，
 * 而不是把技术失败暴露给买家。</p>
 */
class DefaultCapabilityGatewayTenantPolicyTest {

    @Test
    void shouldRejectBlockedCapabilityBeforeProviderRuns() {
        AtomicInteger invocations = new AtomicInteger();
        CapabilityProvider provider = provider("local-returns", "return.apply", invocations);
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(
                List.of(provider),
                new InMemoryCapabilityIdempotencyStore(),
                CapabilityExecutionGuard.sharedDefault(),
                policyBlockingXianyuReturnApply()
        );

        CapabilityResult<?> result = gateway.invoke(
                new CapabilityCall("return.apply", Map.of("order_id", "ORD-001")),
                context("xianyu", "idem-xianyu-return")
        );

        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, result.getOutcome());
        assertEquals("CAPABILITY_BLOCKED_FOR_TENANT", result.getErrorCode());
        assertTrue(result.getMessage().contains("人工客服"), result::getMessage);
        // Provider 从未被执行。
        assertEquals(0, invocations.get());
    }

    @Test
    void shouldAllowSameCapabilityForUnblockedTenantAndHandoffForXianyu() {
        AtomicInteger invocations = new AtomicInteger();
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(
                List.of(
                        provider("local-returns", "return.apply", invocations),
                        provider("local-handoff", "handoff.request", new AtomicInteger())
                ),
                new InMemoryCapabilityIdempotencyStore(),
                CapabilityExecutionGuard.sharedDefault(),
                policyBlockingXianyuReturnApply()
        );

        // 同一能力对其他租户照常执行。
        CapabilityResult<?> defaultTenant = gateway.invoke(
                new CapabilityCall("return.apply", Map.of()),
                context("default", "idem-default-return")
        );
        assertTrue(defaultTenant.isSuccess());
        assertEquals(1, invocations.get());

        // handoff.request 是双层转人工的业务层出口，对 xianyu 也必须放行。
        CapabilityResult<?> handoff = gateway.invoke(
                new CapabilityCall("handoff.request", Map.of()),
                context("xianyu", "idem-xianyu-handoff")
        );
        assertTrue(handoff.isSuccess(), () -> handoff.getErrorCode());
    }

    private CapabilityTenantPolicy policyBlockingXianyuReturnApply() {
        CapabilityTenantPolicy policy = new CapabilityTenantPolicy();
        CapabilityTenantPolicy.TenantRules rules = new CapabilityTenantPolicy.TenantRules();
        rules.setBlockedCapabilities(new LinkedHashSet<>(Set.of("return.apply")));
        policy.getTenantPolicies().put("xianyu", rules);
        return policy;
    }

    private ExecutionContext context(String tenantId, String idempotencyKey) {
        return new ExecutionContext(
                "trace-tenant-policy",
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER")),
                tenantId,
                "conv-tenant-policy",
                idempotencyKey,
                Instant.now().plusSeconds(10)
        );
    }

    private CapabilityProvider provider(String providerId, String capabilityId, AtomicInteger invocations) {
        return new CapabilityProvider() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public Collection<CapabilityDefinition> definitions() {
                return List.of(new CapabilityDefinition(
                        capabilityId,
                        "测试能力",
                        Map.of("type", "object"),
                        Map.of("type", "object")
                ));
            }

            @Override
            public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
                invocations.incrementAndGet();
                return CapabilityResult.success(call.getCapabilityId(), providerId, "ok", Map.of());
            }
        };
    }
}
