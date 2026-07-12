package com.smartcs.smart_cs_agent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 签名 JWT、租户 claim 和本地 Demo 签发入口的配置。 */
@Component
@ConfigurationProperties(prefix = "smartcs.security")
public class SecurityProperties {

    private static final int HS256_MIN_SECRET_BYTES = 32;

    /** 本地学习工作台是否允许公开获取 Demo 用户的短期 JWT。生产环境必须关闭。 */
    private boolean demoIssuerEnabled = true;

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
