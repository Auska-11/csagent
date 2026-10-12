package com.smartcs.smart_cs_agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void missingSecretMustFailStartupEvenWhenDemoIssuerIsDisabled() {
        SecurityProperties unsafe = new SecurityProperties();
        assertNull(unsafe.getJwtSecret());
        IllegalStateException failure = assertThrows(IllegalStateException.class, unsafe::validateJwtSecret);
        assertTrue(failure.getMessage().contains("SMARTCS_SECURITY_JWT_SECRET"));
    }

    @Test
    void knownSecretsMustBeRejectedRegardlessOfDemoIssuerSetting() {
        for (boolean demoEnabled : new boolean[]{false, true}) {
            for (String secret : new String[]{
                    "dev-only-smart-cs-agent-change-this-secret-32bytes",
                    "replace-with-at-least-32-random-bytes"}) {
                SecurityProperties unsafe = new SecurityProperties();
                unsafe.setDemoIssuerEnabled(demoEnabled);
                unsafe.setJwtSecret(secret);
                assertThrows(IllegalStateException.class, unsafe::validateJwtSecret);
            }
        }
    }

    @Test
    void privateSecretShouldAllowBothStartupModes() {
        for (boolean demoEnabled : new boolean[]{false, true}) {
            SecurityProperties safe = new SecurityProperties();
            safe.setDemoIssuerEnabled(demoEnabled);
            safe.setJwtSecret("private-test-secret-with-at-least-32-bytes!!");
            assertDoesNotThrow(safe::validateJwtSecret);
        }
    }

    @Test
    void springStartupMustEnforceSecretValidationAfterPropertyBinding() {
        try (AnnotationConfigApplicationContext missing = securityContext()) {
            assertThrows(BeansException.class, missing::refresh);
        }
        try (AnnotationConfigApplicationContext known = securityContext(
                "smartcs.security.demo-issuer-enabled=false",
                "smartcs.security.jwt-secret=dev-only-smart-cs-agent-change-this-secret-32bytes")) {
            assertThrows(BeansException.class, known::refresh);
        }
        try (AnnotationConfigApplicationContext safe = securityContext(
                "smartcs.security.jwt-secret=private-test-secret-with-at-least-32-bytes!!")) {
            assertDoesNotThrow(safe::refresh);
            assertNotNull(safe.getBean(SecurityProperties.class).getJwtSecret());
        }
    }

    private AnnotationConfigApplicationContext securityContext(String... properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.register(SecurityBindingConfiguration.class);
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class SecurityBindingConfiguration {
    }
}
