package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import com.smartcs.smart_cs_agent.security.DemoAuthenticationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 演示 token 到服务端 Principal 的边界测试。 */
class DemoAuthenticationServiceTest {

    private final DemoAuthenticationService authentication =
            new DemoAuthenticationService(new UserRepository());

    @Test
    void shouldAuthenticateByTokenInsteadOfTrustingUserIdClaim() {
        AuthenticatedPrincipal principal = authentication.authenticate("USER-002", "token-lisi");

        assertTrue(principal.isAuthenticated());
        assertEquals("USER-002", principal.getUserId());
        assertTrue(principal.hasRole("CUSTOMER"));
    }

    @Test
    void shouldKeepTokenlessLegacyRequestExplicitlyUnverified() {
        AuthenticatedPrincipal principal = authentication.authenticate("USER-001", null);

        assertFalse(principal.isAuthenticated());
        assertEquals("USER-001", principal.getUserId());
    }

    @Test
    void shouldRejectTokenAndClaimMismatch() {
        AuthenticationException error = assertThrows(
                AuthenticationException.class,
                () -> authentication.authenticate("USER-001", "token-lisi")
        );

        assertEquals("AUTHENTICATION_FAILED", error.getErrorCode());
    }

    @Test
    void shouldRejectUnknownTokenWithoutLeakingAccountDetails() {
        AuthenticationException error = assertThrows(
                AuthenticationException.class,
                () -> authentication.authenticate("USER-001", "unknown-token")
        );

        assertEquals("AUTHENTICATION_FAILED", error.getErrorCode());
        assertEquals("Authentication failed", error.getMessage());
    }
}
