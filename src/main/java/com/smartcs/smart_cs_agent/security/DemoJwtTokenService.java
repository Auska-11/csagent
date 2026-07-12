package com.smartcs.smart_cs_agent.security;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 本地工作台使用的短期 JWT 签发器。
 *
 * <p>它不是登录系统；生产环境必须关闭 demoIssuerEnabled，并由真实身份系统签发 JWT。</p>
 */
@Service
public class DemoJwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties properties;
    private final Clock clock;

    @Autowired
    public DemoJwtTokenService(JwtEncoder jwtEncoder, SecurityProperties properties) {
        this(jwtEncoder, properties, Clock.systemUTC());
    }

    DemoJwtTokenService(JwtEncoder jwtEncoder, SecurityProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public List<DemoIdentityToken> issueDemoUserTokens() {
        requireDemoIssuer();
        // USER-001 同时作为本地管理员，保证单页工作台可以查看 Monitor / Eval / Tool 面板。
        return List.of(
                issue("USER-001", "Zhang San", Set.of(SecurityRoles.CUSTOMER, SecurityRoles.ADMIN)),
                issue("USER-002", "Li Si", Set.of(SecurityRoles.CUSTOMER))
        );
    }

    public DemoIdentityToken issueMcpClientToken(String subject) {
        requireDemoIssuer();
        return issue(subject, subject, Set.of(SecurityRoles.MCP_CLIENT));
    }

    private DemoIdentityToken issue(String subject, String displayName, Set<String> roles) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(properties.getTokenTtlSeconds());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(subject)
                .audience(List.of(properties.getAudience()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("tenant_id", properties.getDefaultTenantId())
                .claim("roles", roles)
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
        return new DemoIdentityToken(subject, displayName, token, roles, expiresAt.toEpochMilli());
    }

    private void requireDemoIssuer() {
        if (!properties.isDemoIssuerEnabled()) {
            throw AuthenticationException.failed();
        }
    }
}
