package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderListResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderSummary;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.local.LocalOrderCapabilityProvider;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 订单独立 Provider 的定义、身份归属和稳定输出契约测试。 */
class LocalOrderCapabilityProviderTest {

    @Test
    void shouldExposeAuthenticatedReadOnlyDefinitions() {
        LocalOrderCapabilityProvider provider = provider();
        List<CapabilityDefinition> definitions = provider.definitions().stream().toList();

        assertEquals(
                List.of(CapabilityIds.ORDER_LOOKUP, CapabilityIds.ORDER_LIST_BY_USER),
                definitions.stream().map(CapabilityDefinition::getId).toList()
        );
        for (CapabilityDefinition definition : definitions) {
            assertTrue(definition.isRequiresIdentity());
            assertTrue(definition.isRequiresAuthentication());
            assertFalse(definition.isSideEffect());
            assertFalse(definition.isIdempotent());
            assertEquals(CapabilityRiskLevel.READ_ONLY, definition.getRiskLevel());
        }
    }

    @Test
    void shouldNormalizeOrderIdAndReturnOwnedOrderAsStableSummary() {
        CapabilityResult<?> result = gateway().invoke(
                new CapabilityCall(CapabilityIds.ORDER_LOOKUP, Map.of("order_id", "ord-001")),
                authenticated("USER-001")
        );

        OrderSummary order = (OrderSummary) result.getData();
        assertTrue(result.isSuccess());
        assertEquals(LocalOrderCapabilityProvider.PROVIDER_ID, result.getProviderId());
        assertEquals("ORD-001", order.getOrderId());
        assertEquals("蓝牙耳机 Pro Max", order.getProductName());
        assertEquals("SF1234567890", order.getTrackingNo());
    }

    @Test
    void shouldRejectMissingUnknownAndAnotherUsersOrderWithoutLeakingData() {
        DefaultCapabilityGateway gateway = gateway();

        CapabilityResult<?> missingArgument = gateway.invoke(
                new CapabilityCall(CapabilityIds.ORDER_LOOKUP, Map.of()),
                authenticated("USER-001")
        );
        CapabilityResult<?> notFound = gateway.invoke(
                new CapabilityCall(CapabilityIds.ORDER_LOOKUP, Map.of("order_id", "ORD-999")),
                authenticated("USER-001")
        );
        CapabilityResult<?> anotherOwner = gateway.invoke(
                new CapabilityCall(CapabilityIds.ORDER_LOOKUP, Map.of("order_id", "ORD-003")),
                authenticated("USER-001")
        );

        assertRejected(missingArgument, "MISSING_ARGUMENT");
        assertRejected(notFound, "ORDER_NOT_FOUND");
        assertRejected(anotherOwner, "ORDER_OWNERSHIP_MISMATCH");
        assertNull(anotherOwner.getData());
    }

    @Test
    void shouldIgnoreForgedUserIdAndListOnlyAuthenticatedUsersOrders() {
        CapabilityResult<?> result = gateway().invoke(
                new CapabilityCall(
                        CapabilityIds.ORDER_LIST_BY_USER,
                        Map.of("user_id", "USER-002")
                ),
                authenticated("USER-001")
        );

        OrderListResult orders = (OrderListResult) result.getData();
        assertTrue(result.isSuccess());
        assertEquals("USER-001", orders.getUserId());
        assertEquals(2, orders.getCount());
        assertTrue(orders.getOrders().stream().allMatch(order -> "USER-001".equals(order.getUserId())));
    }

    @Test
    void shouldLetGatewayRejectUnverifiedIdentity() {
        ExecutionContext unverified = new ExecutionContext(
                "trace-order",
                AuthenticatedPrincipal.unverifiedClaim("USER-001"),
                "tenant-a",
                "conv-order",
                "",
                Instant.now().plusSeconds(10)
        );

        CapabilityResult<?> result = gateway().invoke(
                new CapabilityCall(CapabilityIds.ORDER_LOOKUP, Map.of("order_id", "ORD-001")),
                unverified
        );

        assertRejected(result, "AUTHENTICATION_REQUIRED");
    }

    private LocalOrderCapabilityProvider provider() {
        return new LocalOrderCapabilityProvider(new OrderRepository(), new ProductRepository());
    }

    private DefaultCapabilityGateway gateway() {
        return new DefaultCapabilityGateway(List.of(provider()));
    }

    private ExecutionContext authenticated(String userId) {
        return new ExecutionContext(
                "trace-order",
                AuthenticatedPrincipal.authenticated("tenant-a", userId, Set.of("CUSTOMER")),
                "tenant-a",
                "conv-order",
                "",
                Instant.now().plusSeconds(10)
        );
    }

    private void assertRejected(CapabilityResult<?> result, String errorCode) {
        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, result.getOutcome());
        assertEquals(errorCode, result.getErrorCode());
    }
}
