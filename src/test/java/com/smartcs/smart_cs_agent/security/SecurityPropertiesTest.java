package com.smartcs.smart_cs_agent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JWT 密钥和有效期配置错误时必须在启动期失败。 */
class SecurityPropertiesTest {

    @Test
    void shouldRejectUnsafeJwtConfiguration() {
        SecurityProperties properties = new SecurityProperties();

        assertThrows(IllegalArgumentException.class, () -> properties.setJwtSecret("too-short"));
        assertThrows(IllegalArgumentException.class, () -> properties.setIssuer(" "));
        assertThrows(IllegalArgumentException.class, () -> properties.setAudience(""));
        assertThrows(IllegalArgumentException.class, () -> properties.setDefaultTenantId(null));
        assertThrows(IllegalArgumentException.class, () -> properties.setTokenTtlSeconds(0));
        assertThrows(IllegalArgumentException.class, () -> properties.setClockSkewSeconds(-1));
    }

    @Test
    void demoIssuerShouldDefaultToDisabled() {
        // Demo 签发公开返回带 ADMIN 角色的 Token，只能作为本地演示配置显式开启。
        assertFalse(new SecurityProperties().isDemoIssuerEnabled());
    }

    @Test
    void demoIssuerWithKnownDevSecretMustFailStartup() {
        SecurityProperties unsafe = new SecurityProperties();
        unsafe.setDemoIssuerEnabled(true);
        // jwtSecret 默认就是仓库公开的开发密钥。

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                unsafe::rejectDemoIssuerWithKnownDevSecret
        );
        assertTrue(failure.getMessage().contains("SMARTCS_SECURITY_JWT_SECRET"));
    }

    @Test
    void demoIssuerWithPrivateSecretShouldStart() {
        SecurityProperties safe = new SecurityProperties();
        safe.setDemoIssuerEnabled(true);
        safe.setJwtSecret("private-test-secret-with-at-least-32-bytes!!");

        assertDoesNotThrow(safe::rejectDemoIssuerWithKnownDevSecret);
    }

    @Test
    void knownDevSecretIsAcceptableWhileDemoIssuerDisabled() {
        SecurityProperties localOnly = new SecurityProperties();
        // 本地裸跑 JAR、不开 demo 签发：允许继续使用开发默认密钥完成非对外学习场景。
        assertFalse(localOnly.isDemoIssuerEnabled());

        assertDoesNotThrow(localOnly::rejectDemoIssuerWithKnownDevSecret);
    }
}
