package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CapabilityGateway 的纯单元测试。
 *
 * <p>测试使用 Fake Provider，证明 Gateway 的分发只依赖 CapabilityProvider 接口；未来换成本地实现
 * 或 MCP Client 时，Gateway 核心代码和这些行为约束都不需要改变。</p>
 */
class DefaultCapabilityGatewayTest {

    @Test
    void shouldDispatchCallAndExecutionContextToRegisteredProvider() {
        AtomicReference<ExecutionContext> receivedContext = new AtomicReference<>();
        CapabilityProvider provider = provider("local-orders", "order.lookup", receivedContext);
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(provider));
        ExecutionContext context = new ExecutionContext(
                "trace-001",
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER")),
                "tenant-a",
                "conv-001",
                "idem-001",
                Instant.now().plusSeconds(10)
        );

        CapabilityResult<?> result = gateway.invoke(
                new CapabilityCall("order.lookup", Map.of("order_id", "ORD-001")),
                context
        );

        assertTrue(result.isSuccess());
        assertEquals("local-orders", result.getProviderId());
        assertEquals(Map.of("order_id", "ORD-001"), result.getData());
        assertSame(context, receivedContext.get());
        assertTrue(gateway.catalog().contains("order.lookup"));
    }

    @Test
    void shouldReturnStructuredFailureForUnknownCapability() {
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of());

        CapabilityResult<?> result = gateway.invoke(
                new CapabilityCall("unknown.capability", Map.of()),
                ExecutionContext.legacy()
        );

        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, result.getOutcome());
        assertEquals("CAPABILITY_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void shouldRejectDuplicateCapabilityRegistrationAtStartup() {
        CapabilityProvider first = provider("provider-a", "order.lookup", new AtomicReference<>());
        CapabilityProvider second = provider("provider-b", "order.lookup", new AtomicReference<>());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DefaultCapabilityGateway(List.of(first, second))
        );

        assertTrue(exception.getMessage().contains("order.lookup"));
        assertTrue(exception.getMessage().contains("provider-a"));
        assertTrue(exception.getMessage().contains("provider-b"));
    }

    @Test
    void shouldStopBeforeProviderWhenDeadlineAlreadyExpired() {
        AtomicReference<ExecutionContext> receivedContext = new AtomicReference<>();
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(
                List.of(provider("local-orders", "order.lookup", receivedContext))
        );
        ExecutionContext expired = new ExecutionContext(
                "trace-expired",
                AuthenticatedPrincipal.unverifiedClaim("USER-001"),
                "",
                "conv-expired",
                "",
                Instant.now().minusSeconds(1)
        );

        CapabilityResult<?> result = gateway.invoke(
                new CapabilityCall("order.lookup", Map.of()),
                expired
        );

        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, result.getOutcome());
        assertEquals("CAPABILITY_DEADLINE_EXCEEDED", result.getErrorCode());
        assertEquals(null, receivedContext.get());
    }

    private CapabilityProvider provider(String providerId,
                                        String capabilityId,
                                        AtomicReference<ExecutionContext> receivedContext) {
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
                receivedContext.set(context);
                return CapabilityResult.success(
                        call.getCapabilityId(),
                        providerId,
                        "ok",
                        call.getArguments()
                );
            }
        };
    }
}
