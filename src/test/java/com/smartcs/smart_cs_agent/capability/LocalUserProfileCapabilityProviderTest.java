package com.smartcs.smart_cs_agent.capability;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ExecutionContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityCall;
import com.smartcs.smart_cs_agent.capability.api.CapabilityDefinition;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.CapabilityRiskLevel;
import com.smartcs.smart_cs_agent.capability.api.contracts.UserProfileSummary;
import com.smartcs.smart_cs_agent.capability.core.DefaultCapabilityGateway;
import com.smartcs.smart_cs_agent.capability.local.LocalUserProfileCapabilityProvider;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** user.lookup 独立 Provider 的 Principal 注入、脱敏和安全元数据测试。 */
class LocalUserProfileCapabilityProviderTest {

    @Test
    void shouldExposeAuthenticatedReadOnlyDefinition() {
        LocalUserProfileCapabilityProvider provider = provider();
        CapabilityDefinition definition = provider.definitions().iterator().next();

        assertEquals(CapabilityIds.USER_LOOKUP, definition.getId());
        assertTrue(definition.isRequiresIdentity());
        assertTrue(definition.isRequiresAuthentication());
        assertFalse(definition.isSideEffect());
        assertFalse(definition.isIdempotent());
        assertEquals(CapabilityRiskLevel.READ_ONLY, definition.getRiskLevel());
    }

    @Test
    void shouldIgnoreForgedArgumentsAndReturnMaskedPrincipalProfile() {
        CapabilityResult<?> result = gateway().invoke(
                new CapabilityCall(CapabilityIds.USER_LOOKUP, Map.of(
                        "user_id", "USER-002",
                        "token", "token-lisi"
                )),
                authenticated("USER-001")
        );

        UserProfileSummary profile = (UserProfileSummary) result.getData();
        assertTrue(result.isSuccess());
        assertEquals("USER-001", profile.getUserId());
        assertEquals("张三", profile.getName());
        assertEquals("138****1234", profile.getPhoneMasked());
    }

    @Test
    void shouldRejectUnverifiedIdentityAndMissingAuthenticatedProfile() {
        DefaultCapabilityGateway gateway = gateway();
        CapabilityResult<?> unverified = gateway.invoke(
                new CapabilityCall(CapabilityIds.USER_LOOKUP, Map.of()),
                new ExecutionContext(
                        "trace-user",
                        AuthenticatedPrincipal.unverifiedClaim("USER-001"),
                        "tenant-a",
                        "conv-user",
                        "",
                        Instant.now().plusSeconds(10)
                )
        );
        CapabilityResult<?> missing = gateway.invoke(
                new CapabilityCall(CapabilityIds.USER_LOOKUP, Map.of()),
                authenticated("USER-999")
        );

        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, unverified.getOutcome());
        assertEquals("AUTHENTICATION_REQUIRED", unverified.getErrorCode());
        assertEquals(CapabilityOutcome.BUSINESS_REJECTED, missing.getOutcome());
        assertEquals("USER_NOT_FOUND", missing.getErrorCode());
        assertNull(missing.getData());
    }

    private LocalUserProfileCapabilityProvider provider() {
        return new LocalUserProfileCapabilityProvider(new UserRepository());
    }

    private DefaultCapabilityGateway gateway() {
        return new DefaultCapabilityGateway(List.of(provider()));
    }

    private ExecutionContext authenticated(String userId) {
        return new ExecutionContext(
                "trace-user",
                AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER")),
                "tenant-a",
                "conv-user",
                "",
                Instant.now().plusSeconds(10)
        );
    }
}
