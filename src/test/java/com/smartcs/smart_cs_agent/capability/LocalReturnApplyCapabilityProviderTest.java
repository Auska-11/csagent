package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.ReturnApplicationSummary;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.idempotency.memory.InMemoryCapabilityIdempotencyStore;
import com.smartcs.smart_cs_agent.capability.local.LocalReturnApplyCapabilityProvider;
import com.smartcs.smart_cs_agent.domain.returnservice.ReturnApplicationService;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** return.apply 独立 Local Provider 与领域服务的契约测试。 */
class LocalReturnApplyCapabilityProviderTest {

    @Test
    void shouldExposeCompleteSecurityMetadataAndReplayWithoutSecondWrite() {
        Fixture fixture = fixture();
        CapabilityDefinition definition = fixture.provider.definitions().iterator().next();
        ExecutionContext context = authenticated("USER-002", "idem-domain-replay");
        CapabilityCall call = call("ORD-003", "质量问题");

        CapabilityResult<?> first = fixture.gateway.invoke(call, context);
        CapabilityResult<?> replay = fixture.gateway.invoke(call, context);

        assertEquals("2.0.0", definition.getVersion());
        assertTrue(definition.isRequiresIdentity());
        assertTrue(definition.isRequiresAuthentication());
        assertTrue(definition.isSideEffect());
        assertTrue(definition.isIdempotent());
        assertTrue(first.isSuccess());
        assertTrue(replay.isReplayed());
        ReturnApplicationSummary summary = (ReturnApplicationSummary) first.getData();
        assertEquals("ORD-003", summary.getOrderId());
        assertTrue(summary.isSubmitted());
        assertNotNull(fixture.returnRepository.findByOrderId("ORD-003"));
    }

    @Test
    void shouldReturnBusinessRejectionsForOwnershipStatusAndMissingOrder() {
        Fixture fixture = fixture();

        CapabilityResult<?> wrongOwner = fixture.gateway.invoke(
                call("ORD-003", "质量问题"),
                authenticated("USER-001", "idem-owner")
        );
        CapabilityResult<?> invalidStatus = fixture.gateway.invoke(
                call("ORD-001", "不想要了"),
                authenticated("USER-001", "idem-status")
        );
        CapabilityResult<?> missing = fixture.gateway.invoke(
                call("ORD-999", "质量问题"),
                authenticated("USER-002", "idem-missing")
        );

        assertRejected(wrongOwner, "ORDER_OWNERSHIP_MISMATCH");
        assertRejected(invalidStatus, "ORDER_STATUS_NOT_RETURNABLE");
        assertRejected(missing, "ORDER_NOT_FOUND");
        assertEquals(null, fixture.returnRepository.findByOrderId("ORD-003"));
    }

    @Test
    void shouldRejectSecondBusinessRequestEvenWithDifferentIdempotencyKey() {
        Fixture fixture = fixture();
        CapabilityCall call = call("ORD-003", "质量问题");

        CapabilityResult<?> first = fixture.gateway.invoke(
                call,
                authenticated("USER-002", "idem-first")
        );
        CapabilityResult<?> secondBusinessRequest = fixture.gateway.invoke(
                call,
                authenticated("USER-002", "idem-second")
        );

        assertTrue(first.isSuccess());
        assertRejected(secondBusinessRequest, "DUPLICATE_RETURN_APPLICATION");
    }

    @Test
    void concurrentRequestsWithDifferentKeysShouldStillCreateOnlyOneReturn() throws Exception {
        Fixture fixture = fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startTogether = new CountDownLatch(1);

        try {
            Future<CapabilityResult<?>> first = executor.submit(() -> {
                await(startTogether);
                return fixture.gateway.invoke(
                        call("ORD-003", "质量问题"),
                        authenticated("USER-002", "idem-concurrent-a")
                );
            });
            Future<CapabilityResult<?>> second = executor.submit(() -> {
                await(startTogether);
                return fixture.gateway.invoke(
                        call("ORD-003", "质量问题"),
                        authenticated("USER-002", "idem-concurrent-b")
                );
            });

            startTogether.countDown();
            List<CapabilityResult<?>> results = List.of(
                    first.get(2, TimeUnit.SECONDS),
                    second.get(2, TimeUnit.SECONDS)
            );

            assertEquals(1, results.stream().filter(CapabilityResult::isSuccess).count());
            assertEquals(
                    1,
                    results.stream()
                            .filter(result -> result.getOutcome() == CapabilityOutcome.BUSINESS_REJECTED)
                            .count()
            );
            assertNotNull(fixture.returnRepository.findByOrderId("ORD-003"));
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture fixture() {
        ProductRepository products = new ProductRepository();
        OrderRepository orders = new OrderRepository();
        ReturnRepository returns = new ReturnRepository();
        ReturnApplicationService service = new ReturnApplicationService(returns, orders, products);
        LocalReturnApplyCapabilityProvider provider = new LocalReturnApplyCapabilityProvider(service);
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(
                List.of(provider),
                new InMemoryCapabilityIdempotencyStore()
        );
        return new Fixture(provider, gateway, returns);
    }

    private CapabilityCall call(String orderId, String reason) {
        return new CapabilityCall(
                CapabilityIds.RETURN_APPLY,
                Map.of("order_id", orderId, "reason", reason)
        );
    }

    private ExecutionContext authenticated(String userId, String idempotencyKey) {
        return new ExecutionContext(
                "trace-domain",
                AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER")),
                "tenant-a",
                "conv-domain",
                idempotencyKey,
                Instant.now().plusSeconds(10)
        );
    }

    private void assertRejected(CapabilityResult<?> result, String code) {
        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, result.getOutcome());
        assertEquals(code, result.getErrorCode());
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

    private record Fixture(LocalReturnApplyCapabilityProvider provider,
                           DefaultCapabilityGateway gateway,
                           ReturnRepository returnRepository) {
    }
}
