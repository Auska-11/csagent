package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSidecarProperties;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.smartcs.smart_cs_agent.security.JwtPrincipalConverter.VerifiedIdentityDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Set;

/** Browser identity comes from a verified JWT, including expiry and channel scope. */
@RestController
public class AuthIdentityController {
    private final XianyuSidecarProperties properties;
    public AuthIdentityController(XianyuSidecarProperties properties) { this.properties = properties; }
    public record Identity(String userId, String name, Set<String> roles, long expiresAt, String tenantId, String channel) { }

    @GetMapping("/api/auth/me")
    public Identity me(@AuthenticationPrincipal AuthenticatedPrincipal principal, Authentication authentication) {
        VerifiedIdentityDetails details = (VerifiedIdentityDetails) authentication.getDetails();
        return new Identity(principal.getUserId(), principal.getUserId(), principal.getRoles(),
                details.expiresAt(), principal.getTenantId(),
                properties.getTenantId().equals(principal.getTenantId()) ? "xianyu" : "demo");
    }
}
