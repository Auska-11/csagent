package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.security.SecurityProperties;
import com.smartcs.smart_cs_agent.security.SecurityRoles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * 为闲鱼买家签发合成用户 JWT。
 *
 * <p>与 DemoJwtTokenService 共用同一签发器、issuer/audience 和解析链路：侧车随后用该
 * 令牌走标准 Bearer 认证调用 /api/chat/send，Spring Security 按普通 CUSTOMER 身份校验，
 * 通道层不需要任何新角色或新解析逻辑。</p>
 */
@Component
public class XianyuBuyerTokenService {

    private final JwtEncoder jwtEncoder;
    private final SecurityProperties securityProperties;
    private final XianyuSidecarProperties properties;
    private final Clock clock;

    @Autowired
    XianyuBuyerTokenService(JwtEncoder jwtEncoder,
                            SecurityProperties securityProperties,
                            XianyuSidecarProperties properties) {
        this(jwtEncoder, securityProperties, properties, Clock.systemUTC());
    }

    XianyuBuyerTokenService(JwtEncoder jwtEncoder,
                            SecurityProperties securityProperties,
                            XianyuSidecarProperties properties,
                            Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.securityProperties = securityProperties;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedBuyerToken issue(String userId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusSeconds(properties.getBuyerTokenTtlSeconds());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(securityProperties.getIssuer())
                .subject(userId)
                .audience(java.util.List.of(securityProperties.getAudience()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("tenant_id", properties.getTenantId())
                .claim("roles", Set.of(SecurityRoles.CUSTOMER))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
        return new IssuedBuyerToken(userId, token, expiresAt.toEpochMilli());
    }

    /** 签发结果：userId 同时回传，侧车侧不再自行计算映射。 */
    record IssuedBuyerToken(String userId, String token, long expiresAtEpochMillis) {
    }
}
