package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.HandoffTicketSummary;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.local.LocalHandoffCapabilityProvider;
import com.smartcs.smart_cs_agent.tools.HandoffTicketData;
import com.smartcs.smart_cs_agent.tools.HandoffProperties;
import com.smartcs.smart_cs_agent.tools.HandoffTools;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** handoff.request 独立 Provider 的可信上下文、稳定契约和幂等测试。 */
class LocalHandoffCapabilityProviderTest {

    @Test
    void shouldExposeSideEffectDefinitionAndOverrideLegacyTool() {
        LocalHandoffCapabilityProvider provider = new LocalHandoffCapabilityProvider(new HandoffTools());
        CapabilityDefinition definition = provider.definitions().iterator().next();

        assertEquals(CapabilityIds.HANDOFF_REQUEST, definition.getId());
        assertTrue(definition.isRequiresIdentity());
        assertFalse(definition.isRequiresAuthentication());
        assertTrue(definition.isSideEffect());
        assertTrue(definition.isIdempotent());
        assertEquals(CapabilityRiskLevel.MEDIUM, definition.getRiskLevel());
        assertEquals(Set.of(CapabilityIds.HANDOFF_REQUEST), provider.overriddenCapabilityIds());
    }

    @Test
    void shouldIgnoreForgedIdentityArgumentsAndUseExecutionContext() {
        HandoffTools tools = mock(HandoffTools.class);
        when(tools.createHandoffTicket(
                "USER-001",
                "conv-trusted",
                "COMPLAINT",
                "用户投诉服务问题"
        )).thenReturn(new HandoffTicketData(
                "HD-2001",
                "USER-001",
                "conv-trusted",
                "COMPLAINT",
                "用户投诉服务问题",
                "WAITING_HUMAN_AGENT",
                "2026-07-12T12:00:00"
        ));
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(
                new LocalHandoffCapabilityProvider(tools)
        ));

        CapabilityResult<?> result = gateway.invoke(
                new CapabilityCall(CapabilityIds.HANDOFF_REQUEST, Map.of(
                        "reason", "COMPLAINT",
                        "summary", "用户投诉服务问题",
                        "user_id", "USER-999",
                        "conversation_id", "conv-forged"
                )),
                context(
                        AuthenticatedPrincipal.unverifiedClaim("USER-001"),
                        "conv-trusted",
                        "idem-trusted"
                )
        );

        HandoffTicketSummary ticket = (HandoffTicketSummary) result.getData();
        assertTrue(result.isSuccess());
        assertEquals("HD-2001", ticket.getTicketId());
        assertEquals("COMPLAINT", ticket.getReason());
        verify(tools).createHandoffTicket(
                "USER-001",
                "conv-trusted",
                "COMPLAINT",
                "用户投诉服务问题"
        );
    }

    @Test
    void shouldReplaySuccessfulTicketForSameIdempotencyKey() {
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(
                new LocalHandoffCapabilityProvider(new HandoffTools())
        ));
        CapabilityCall call = new CapabilityCall(CapabilityIds.HANDOFF_REQUEST, Map.of(
                "reason", "USER_REQUESTED_HUMAN",
                "summary", "用户要求人工客服"
        ));
        ExecutionContext context = context(
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER")),
                "conv-replay",
                "idem-replay"
        );

        CapabilityResult<?> first = gateway.invoke(call, context);
        CapabilityResult<?> second = gateway.invoke(call, context);

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertTrue(second.isReplayed());
        assertEquals(
                ((HandoffTicketSummary) first.getData()).getTicketId(),
                ((HandoffTicketSummary) second.getData()).getTicketId()
        );
    }

    @Test
    void shouldRejectMissingSummaryWithoutCallingTicketSystem() {
        HandoffTools tools = mock(HandoffTools.class);
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(
                new LocalHandoffCapabilityProvider(tools)
        ));

        CapabilityResult<?> result = gateway.invoke(
                new CapabilityCall(CapabilityIds.HANDOFF_REQUEST, Map.of("reason", "COMPLAINT")),
                context(
                        AuthenticatedPrincipal.unverifiedClaim("USER-001"),
                        "conv-missing",
                        "idem-missing"
                )
        );

        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, result.getOutcome());
        assertEquals("MISSING_ARGUMENT", result.getErrorCode());
    }

    @Test
    void shouldReturnAndReplayTechnicalFailureWhenRemoteTicketResultIsUnknown() {
        HandoffProperties properties = new HandoffProperties();
        properties.setMode("after-sales-http");
        AtomicInteger remoteCalls = new AtomicInteger();
        HandoffTools tools = new HandoffTools(properties, request -> {
            remoteCalls.incrementAndGet();
            throw new IllegalStateException("http://internal-ticket password=do-not-leak");
        });
        DefaultCapabilityGateway gateway = new DefaultCapabilityGateway(List.of(
                new LocalHandoffCapabilityProvider(tools)
        ));
        CapabilityCall call = new CapabilityCall(CapabilityIds.HANDOFF_REQUEST, Map.of(
                "reason", "COMPLAINT",
                "summary", "用户投诉，需要人工处理"
        ));
        ExecutionContext context = context(
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER")),
                "conv-remote-unknown",
                "idem-remote-unknown"
        );

        CapabilityResult<?> first = gateway.invoke(call, context);
        CapabilityResult<?> second = gateway.invoke(call, context);

        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, first.getOutcome());
        assertEquals("CAPABILITY_PROVIDER_ERROR", first.getErrorCode());
        assertFalse(first.getMessage().contains("internal-ticket"));
        assertFalse(first.getMessage().contains("do-not-leak"));
        assertEquals(CapabilityOutcome.TECHNICAL_FAILURE, second.getOutcome());
        assertTrue(second.isReplayed());
        assertEquals(1, remoteCalls.get());
    }

    private ExecutionContext context(AuthenticatedPrincipal principal,
                                     String conversationId,
                                     String idempotencyKey) {
        return new ExecutionContext(
                "trace-handoff",
                principal,
                "tenant-a",
                conversationId,
                idempotencyKey,
                Instant.now().plusSeconds(10)
        );
    }
}
