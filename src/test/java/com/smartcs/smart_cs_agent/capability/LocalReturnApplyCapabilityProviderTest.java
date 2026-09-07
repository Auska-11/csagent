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

    /**
     * 用户复现过的缺陷：两个不同订单并发申请退货时，COUNT+1 单号撞车 + upsert 覆盖，
     * 导致两笔都“成功”但只留下一笔记录。现在两笔必须都成功、单号不同、且两条记录都存在。
     */
    @Test
    void concurrentDifferentOrdersShouldBothSucceedWithDistinctReturnIds() throws Exception {
        Fixture fixture = fixture();
        // ORD-004 已有种子退货单，这里补一个干净的可退货订单作为第二目标。
        fixture.orderRepository().save(new com.smartcs.smart_cs_agent.model.Order(
                "ORD-101", "USER-002", "PROD-003", "已签收", "YT101"
        ));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startTogether = new CountDownLatch(1);

        try {
            Future<CapabilityResult<?>> first = executor.submit(() -> {
                await(startTogether);
                return fixture.gateway.invoke(
                        call("ORD-003", "质量问题"),
                        authenticated("USER-002", "idem-cross-a")
                );
            });
            Future<CapabilityResult<?>> second = executor.submit(() -> {
                await(startTogether);
                return fixture.gateway.invoke(
                        call("ORD-101", "尺寸不合适"),
                        authenticated("USER-002", "idem-cross-b")
                );
            });

            startTogether.countDown();
            List<CapabilityResult<?>> results = List.of(
                    first.get(2, TimeUnit.SECONDS),
                    second.get(2, TimeUnit.SECONDS)
            );

            assertEquals(2, results.stream().filter(CapabilityResult::isSuccess).count());
            List<String> returnIds = results.stream()
                    .map(result -> ((ReturnApplicationSummary) result.getData()).getReturnId())
                    .toList();
            assertEquals(2, returnIds.stream().distinct().count());
            for (String returnId : returnIds) {
                assertNotNull(fixture.returnRepository.findByReturnId(returnId));
            }
            assertNotNull(fixture.returnRepository.findByOrderId("ORD-003"));
            assertNotNull(fixture.returnRepository.findByOrderId("ORD-101"));
            assertEquals("RET-001", fixture.returnRepository.findByOrderId("ORD-004").getReturnId());
        } finally {
            executor.shutdownNow();
        }
    }

    /** 单号生成不再依赖记录数：同一毫秒内连续生成的单号也必须互不相同。 */
    @Test
    void generatedReturnIdsMustBeUniqueUnderBurst() {
        Fixture fixture = fixture();
        Set<String> ids = new java.util.HashSet<>();

        for (int i = 0; i < 500; i++) {
            String returnId = fixture.returnRepository.generateReturnId();
            assertTrue(returnId.startsWith("RET-"));
            assertTrue(ids.add(returnId), "重复退换单号: " + returnId);
        }
    }

    /** order_id 已有退货记录时 create 必须拒绝，不能覆盖或并存第二条记录。 */
    @Test
    void createMustRejectSecondRecordForSameOrder() {
        Fixture fixture = fixture();
        fixture.returnRepository.save(new com.smartcs.smart_cs_agent.model.ReturnRecord(
                "RET-SEED", "ORD-003", "预置退货", "审核通过"
        ));

        boolean created = fixture.returnRepository.create(new com.smartcs.smart_cs_agent.model.ReturnRecord(
                fixture.returnRepository.generateReturnId(), "ORD-003", "第二次申请", "Under Review"
        ));

        assertEquals(false, created);
        assertEquals("RET-SEED", fixture.returnRepository.findByOrderId("ORD-003").getReturnId());
    }

    /** 不同租户出现相同 userId 时，tenant-b 的同名用户不能给 default 租户的订单申请退货。 */
    @Test
    void shouldRejectCrossTenantReturnApplicationWithSameUserId() {
        Fixture fixture = fixture();
        ExecutionContext crossTenant = new ExecutionContext(
                "trace-domain",
                AuthenticatedPrincipal.authenticated("tenant-b", "USER-002", Set.of("CUSTOMER")),
                "tenant-b",
                "conv-domain",
                "idem-cross-tenant",
                Instant.now().plusSeconds(10)
        );

        CapabilityResult<?> result = fixture.gateway.invoke(call("ORD-003", "质量问题"), crossTenant);

        assertRejected(result, "ORDER_OWNERSHIP_MISMATCH");
        assertEquals(null, fixture.returnRepository.findByOrderId("ORD-003"));
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
        return new Fixture(provider, gateway, orders, returns);
    }

    private CapabilityCall call(String orderId, String reason) {
        return new CapabilityCall(
                CapabilityIds.RETURN_APPLY,
                Map.of("order_id", orderId, "reason", reason)
        );
    }

    private ExecutionContext authenticated(String userId, String idempotencyKey) {
        // 演示种子订单属于默认租户；principal 与上下文的租户保持一致，模拟真实 JWT 链路。
        return new ExecutionContext(
                "trace-domain",
                AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER")),
                "default",
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
                           OrderRepository orderRepository,
                           ReturnRepository returnRepository) {
    }
}
