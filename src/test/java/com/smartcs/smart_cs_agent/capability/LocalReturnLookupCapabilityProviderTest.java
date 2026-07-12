package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.ReturnStatusSummary;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.local.LocalReturnLookupCapabilityProvider;
import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.repository.OrderRepository;
import com.smartcs.smart_cs_agent.repository.ProductRepository;
import com.smartcs.smart_cs_agent.repository.ReturnRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证本地 return.lookup Provider 的输出契约、身份校验和数据完整性保护。 */
class LocalReturnLookupCapabilityProviderTest {

    @Test
    void shouldExposePrivateReadOnlyDefinitionAndReturnStableSummary() {
        Fixture fixture = fixture();
        CapabilityDefinition definition = fixture.provider.definitions().iterator().next();

        CapabilityResult<?> result = fixture.gateway.invoke(
                call("ret-001"),
                authenticated("USER-002")
        );

        assertEquals(CapabilityIds.RETURN_LOOKUP, definition.getId());
        assertEquals("1.0.0", definition.getVersion());
        assertTrue(definition.isRequiresIdentity());
        assertTrue(definition.isRequiresAuthentication());
        assertFalse(definition.isSideEffect());
        assertFalse(definition.isIdempotent());
        assertEquals(CapabilityRiskLevel.READ_ONLY, definition.getRiskLevel());

        ReturnStatusSummary summary = (ReturnStatusSummary) result.getData();
        assertTrue(result.isSuccess());
        assertEquals("RET-001", summary.getReturnId());
        assertEquals("ORD-004", summary.getOrderId());
        assertEquals("Type-C 数据线", summary.getProductName());
        assertEquals("审核通过", summary.getStatus());
    }

    @Test
    void shouldRejectMissingReturnWithoutInventingData() {
        CapabilityResult<?> result = fixture().gateway.invoke(
                call("RET-999"),
                authenticated("USER-002")
        );

        assertRejected(result, "RETURN_NOT_FOUND");
        assertNull(result.getData());
    }

    @Test
    void shouldRejectAnotherOwnerWithoutLeakingReturnData() {
        CapabilityResult<?> result = fixture().gateway.invoke(
                call("RET-001"),
                authenticated("USER-001")
        );

        assertRejected(result, "RETURN_OWNERSHIP_MISMATCH");
        assertNull(result.getData());
    }

    @Test
    void shouldReportBrokenReturnToOrderLinkAsTechnicalFailure() {
        Fixture fixture = fixture();
        // 构造一条“退货记录存在，但关联订单已丢失”的坏数据，验证 Provider 不会假装查询成功。
        fixture.returnRepository.save(new ReturnRecord(
                "RET-999",
                "ORD-999",
                "测试数据完整性",
                "审核中"
        ));

        CapabilityResult<?> result = fixture.gateway.invoke(
                call("RET-999"),
                authenticated("USER-002")
        );

        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, result.getOutcome());
        assertEquals("RETURN_ORDER_INCONSISTENT", result.getErrorCode());
        assertNull(result.getData());
    }

    @Test
    void shouldLetGatewayRejectUnverifiedIdentityBeforeRepositoryAccess() {
        Fixture fixture = fixture();
        ExecutionContext unverified = new ExecutionContext(
                "trace-return-lookup",
                AuthenticatedPrincipal.unverifiedClaim("USER-002"),
                "tenant-a",
                "conv-return-lookup",
                "",
                Instant.now().plusSeconds(10)
        );

        CapabilityResult<?> result = fixture.gateway.invoke(call("RET-001"), unverified);

        assertRejected(result, "AUTHENTICATION_REQUIRED");
    }

    private Fixture fixture() {
        ReturnRepository returns = new ReturnRepository();
        LocalReturnLookupCapabilityProvider provider = new LocalReturnLookupCapabilityProvider(
                returns,
                new OrderRepository(),
                new ProductRepository()
        );
        return new Fixture(provider, new DefaultCapabilityGateway(List.of(provider)), returns);
    }

    private CapabilityCall call(String returnId) {
        return new CapabilityCall(
                CapabilityIds.RETURN_LOOKUP,
                Map.of("return_id", returnId)
        );
    }

    private ExecutionContext authenticated(String userId) {
        return new ExecutionContext(
                "trace-return-lookup",
                AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER")),
                "tenant-a",
                "conv-return-lookup",
                "",
                Instant.now().plusSeconds(10)
        );
    }

    private void assertRejected(CapabilityResult<?> result, String code) {
        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, result.getOutcome());
        assertEquals(code, result.getErrorCode());
    }

    private record Fixture(LocalReturnLookupCapabilityProvider provider,
                           DefaultCapabilityGateway gateway,
                           ReturnRepository returnRepository) {
    }
}
