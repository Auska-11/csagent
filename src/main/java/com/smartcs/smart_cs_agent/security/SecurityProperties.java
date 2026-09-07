package com.smartcs.smart_cs_agent.security;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 签名 JWT、租户 claim 和本地 Demo 签发入口的配置。 */
@Component
@ConfigurationProperties(prefix = "smartcs.security")
public class SecurityProperties {

    private static final int HS256_MIN_SECRET_BYTES = 32;

    /**
     * 仓库里公开可见的开发默认密钥。
     *
     * <p>任何人在读到源码后都能用它们伪造 JWT，因此“开启 Demo 签发 + 仍是开发默认密钥”
     * 这个组合必须直接启动失败，而不是带着可伪造的身份体系上线。</p>
     */
    private static final Set<String> KNOWN_DEV_SECRETS = Set.of(
            "dev-only-smart-cs-agent-change-this-secret-32bytes",
            "replace-with-at-least-32-random-bytes"
    );

    /**
     * 本地学习工作台是否允许公开获取 Demo 用户的短期 JWT。生产环境必须关闭。
     *
     * <p>默认关闭：Demo 签发会公开返回带 ADMIN 角色的短期 Token，属于显式的本地演示配置，
     * 必须通过 docker-compose.demo.yml 或环境变量主动打开，不能跟着部署默认值带上线。</p>
     */
    private boolean demoIssuerEnabled = false;

    private String issuer = "smart-cs-agent";
    private String audience = "smart-cs-api";
    private String defaultTenantId = "default";

    /** 仅供本地启动的默认值；部署时必须通过环境变量替换。 */
    private String jwtSecret = "dev-only-smart-cs-agent-change-this-secret-32bytes";

    private long tokenTtlSeconds = 28_800;
    private long clockSkewSeconds = 30;

    public boolean isDemoIssuerEnabled() {
        return demoIssuerEnabled;
    }

    public void setDemoIssuerEnabled(boolean demoIssuerEnabled) {
        this.demoIssuerEnabled = demoIssuerEnabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = requireText("issuer", issuer);
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = requireText("audience", audience);
    }

    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    public void setDefaultTenantId(String defaultTenantId) {
        this.defaultTenantId = requireText("defaultTenantId", defaultTenantId);
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        String normalized = requireText("jwtSecret", jwtSecret);
        if (normalized.getBytes(StandardCharsets.UTF_8).length < HS256_MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("jwtSecret must contain at least 32 UTF-8 bytes");
        }
        this.jwtSecret = normalized;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = requirePositive("tokenTtlSeconds", tokenTtlSeconds);
    }

    public long getClockSkewSeconds() {
        return clockSkewSeconds;
    }

    public void setClockSkewSeconds(long clockSkewSeconds) {
        if (clockSkewSeconds < 0) {
            throw new IllegalArgumentException("clockSkewSeconds cannot be negative");
        }
        this.clockSkewSeconds = clockSkewSeconds;
    }

    /**
     * 组合校验：Demo 签发对公网开放时，JWT 密钥绝不能仍是仓库里的开发默认值。
     *
     * <p>这是 compose 必填密钥之外的最后一道代码防线：即使有人绕过部署配置直接开
     * demo-issuer-enabled，只要密钥还是公开已知的开发值，应用就拒绝启动。</p>
     */
    @PostConstruct
    void rejectDemoIssuerWithKnownDevSecret() {
        if (demoIssuerEnabled && KNOWN_DEV_SECRETS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "smartcs.security.demo-issuer-enabled=true 会公开签发 JWT，但 jwt-secret 仍是仓库公开的"
                            + "开发默认值，任何人都能伪造管理员 Token。请设置 SMARTCS_SECURITY_JWT_SECRET"
                            + " 为至少 32 字节的私有随机密钥，或保持 demo-issuer-enabled=false。"
            );
        }
    }

    private String requireText(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " cannot be empty");
        }
        return value.trim();
    }

    private long requirePositive(String propertyName, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than 0");
        }
        return value;
    }
}
