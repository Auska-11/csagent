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
     * <p>已知签名密钥可用于伪造 JWT。无论 Demo 签发器是否开启，都拒绝这些值。</p>
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

    /** 所有启动方式都必须显式提供私有密钥；没有公开开发默认值。 */
    private String jwtSecret;

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
        String normalized = requireText("SMARTCS_SECURITY_JWT_SECRET", jwtSecret);
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

    /** 裸跑 JAR、IDE 与 Compose 使用相同的启动校验，不能通过关闭签发器绕过。 */
    @PostConstruct
    void validateJwtSecret() {
        if (jwtSecret == null || jwtSecret.isBlank() || KNOWN_DEV_SECRETS.contains(jwtSecret)) {
            throw new IllegalStateException(
                    "请设置 SMARTCS_SECURITY_JWT_SECRET 为至少 32 字节的私有随机密钥；"
                            + "缺失密钥或使用仓库公开的开发密钥时应用拒绝启动。"
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
