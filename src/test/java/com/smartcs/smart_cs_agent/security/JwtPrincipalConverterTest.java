package com.smartcs.smart_cs_agent.security;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 已验证 JWT claim 到 Agent Principal 和 ROLE_ authority 的映射测试。 */
class JwtPrincipalConverterTest {

    @Test
    void shouldMapSubjectTenantAndRoles() {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("signed-token")
                .header("alg", "HS256")
                .subject("USER-001")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .claim("tenant_id", "tenant-a")
                .claim("roles", List.of("customer", "ADMIN"))
                .build();

        var authentication = new JwtPrincipalConverter().convert(jwt);
        AuthenticatedPrincipal principal = (AuthenticatedPrincipal) authentication.getPrincipal();

        assertEquals("USER-001", principal.getName());
        assertEquals("tenant-a", principal.getTenantId());
        assertTrue(principal.hasRole(SecurityRoles.CUSTOMER));
        assertTrue(principal.hasRole(SecurityRoles.ADMIN));
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority())));
    }
}
