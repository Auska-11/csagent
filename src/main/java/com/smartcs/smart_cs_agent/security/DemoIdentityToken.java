package com.smartcs.smart_cs_agent.security;

import java.util.Set;

/** 本地工作台展示的 Demo 身份和短期 JWT。 */
public record DemoIdentityToken(
        String userId,
        String name,
        String token,
        Set<String> roles,
        long expiresAt) {
}
