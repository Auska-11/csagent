package com.smartcs.smart_cs_agent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
