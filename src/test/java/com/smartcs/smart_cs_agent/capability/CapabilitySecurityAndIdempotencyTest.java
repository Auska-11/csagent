package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityProvider;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.core.CapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Capability Gateway 的认证、幂等和 canonical ID 契约测试。 */
class CapabilitySecurityAndIdempotencyTest {

    @Test
    void shouldRejectUnauthenticatedOrMissingIdempotencyContextBeforeProvider() {
        CountingSideEffectProvider provider = new CountingSideEffectProvider();
        DefaultCapabilityGateway gateway = gateway(provider);

        CapabilityResult<?> unauthenticated = gateway.invoke(
                call("质量问题"),
                context(AuthenticatedPrincipal.unverifiedClaim("USER-002"), "idem-1")
        );
        CapabilityResult<?> missingKey = gateway.invoke(
                call("质量问题"),
                context(AuthenticatedPrincipal.authenticated("USER-002", Set.of("CUSTOMER")), "")
        );

        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, unauthenticated.getOutcome());
        assertEquals("AUTHENTICATION_REQUIRED", unauthenticated.getErrorCode());
        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, missingKey.getOutcome());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", missingKey.getErrorCode());
        assertEquals(0, provider.invocations.get());
    }

    @Test
    void shouldReplaySameIdempotencyKeyAndRejectDifferentArguments() {
        CountingSideEffectProvider provider = new CountingSideEffectProvider();
        DefaultCapabilityGateway gateway = gateway(provider);
        ExecutionContext context = context(
                AuthenticatedPrincipal.authenticated("USER-002", Set.of("CUSTOMER")),
                "idem-replay"
        );

        // 故意用旧别名调用，Gateway 必须在 Provider 前规范化为 return.apply。
        CapabilityResult<?> first = gateway.invoke(
                new CapabilityCall("return_apply", Map.of("order_id", "ORD-003", "reason", "质量问题")),
                context
        );
        CapabilityResult<?> replay = gateway.invoke(call("质量问题"), context);
        CapabilityResult<?> conflict = gateway.invoke(call("不想要了"), context);

        assertTrue(first.isSuccess());
        assertFalse(first.isReplayed());
        assertEquals(CapabilityIds.RETURN_APPLY, first.getCapabilityId());
        assertTrue(replay.isSuccess());
        assertTrue(replay.isReplayed());
        assertEquals(1, provider.invocations.get());
        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, conflict.getOutcome());
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.getErrorCode());
        assertEquals(1, provider.invocations.get());
    }

    @Test
    void shouldCoalesceConcurrentCallsWithSameIdempotencyKey() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountingSideEffectProvider provider = new CountingSideEffectProvider(providerEntered, releaseProvider);
        DefaultCapabilityGateway gateway = gateway(provider);
        ExecutionContext context = context(
                AuthenticatedPrincipal.authenticated("USER-002", Set.of("CUSTOMER")),
                "idem-concurrent"
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CapabilityResult<?>> first = executor.submit(() -> gateway.invoke(call("质量问题"), context));
            assertTrue(providerEntered.await(2, TimeUnit.SECONDS));
            Future<CapabilityResult<?>> second = executor.submit(() -> gateway.invoke(call("质量问题"), context));
            releaseProvider.countDown();

            CapabilityResult<?> firstResult = first.get(2, TimeUnit.SECONDS);
            CapabilityResult<?> secondResult = second.get(2, TimeUnit.SECONDS);
            assertTrue(firstResult.isSuccess());
            assertTrue(secondResult.isSuccess());
            assertEquals(1, provider.invocations.get());
            assertTrue(firstResult.isReplayed() ^ secondResult.isReplayed());
        } finally {
            executor.shutdownNow();
        }
    }

    private DefaultCapabilityGateway gateway(CapabilityProvider provider) {
        return new DefaultCapabilityGateway(List.of(provider), new CapabilityIdempotencyStore());
    }

    private CapabilityCall call(String reason) {
        return new CapabilityCall(
                CapabilityIds.RETURN_APPLY,
                Map.of("order_id", "ORD-003", "reason", reason)
        );
    }

    private ExecutionContext context(AuthenticatedPrincipal principal, String idempotencyKey) {
        return new ExecutionContext(
                "trace-security",
                principal,
                "tenant-a",
                "conv-security",
                idempotencyKey,
                Instant.now().plusSeconds(10)
        );
    }

    private static final class CountingSideEffectProvider implements CapabilityProvider {
        private final AtomicInteger invocations = new AtomicInteger();
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private CountingSideEffectProvider() {
            this(null, null);
        }

        private CountingSideEffectProvider(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        public String providerId() {
            return "counting-return-provider";
        }

        @Override
        public Collection<CapabilityDefinition> definitions() {
            return List.of(new CapabilityDefinition(
                    CapabilityIds.RETURN_APPLY,
                    "2.0.0",
                    "测试副作用能力",
                    Map.of("type", "object"),
                    Map.of("type", "object"),
                    true,
                    true,
                    true,
                    true,
                    5_000,
                    CapabilityRiskLevel.HIGH
            ));
        }

        @Override
        public CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context) {
            invocations.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                await(release);
            }
            return CapabilityResult.success(call.getCapabilityId(), providerId(), "submitted", call.getArguments());
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test latch timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test interrupted", e);
            }
        }
    }
}
