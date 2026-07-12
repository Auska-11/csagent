package com.smartcs.smart_cs_agent.agent.context;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.security.Principal;

/**
 * 服务端确认的调用者身份。
 *
 * <p>HTTP 请求进入系统时，JWT 会被统一转换成这个对象。私有 Capability 只能信任这里的
 * tenantId、userId 和 roles，不能信任 LLM、请求体或普通工具参数里的同名字段。
 * {@link #unverifiedClaim(String)} 只服务于尚未迁移完的内部旧调用链：它明确表示
 * “客户端声称自己是谁，但服务端尚未验证”，绝不能被当成已认证身份。</p>
 */
public final class AuthenticatedPrincipal implements Principal {

    private final String tenantId;
    private final String userId;
    private final boolean authenticated;
    private final Set<String> roles;

    private AuthenticatedPrincipal(
            String tenantId,
            String userId,
            boolean authenticated,
            Set<String> roles) {
        this.tenantId = tenantId == null ? "" : tenantId.trim();
        this.userId = userId == null ? "" : userId.trim();
        this.authenticated = authenticated;
        this.roles = Collections.unmodifiableSet(normalizeRoles(roles));
    }

    public static AuthenticatedPrincipal anonymous() {
        return new AuthenticatedPrincipal("", "", false, Set.of());
    }

    public static AuthenticatedPrincipal unverifiedClaim(String userId) {
        return new AuthenticatedPrincipal("", userId, false, Set.of());
    }

    public static AuthenticatedPrincipal authenticated(String userId, Set<String> roles) {
        return authenticated(ConversationKey.DEFAULT_TENANT_ID, userId, roles);
    }

    public static AuthenticatedPrincipal authenticated(
            String tenantId,
            String userId,
            Set<String> roles) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Authenticated tenant id cannot be empty");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated user id cannot be empty");
        }
        return new AuthenticatedPrincipal(tenantId, userId, true, roles);
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUserId() {
        return userId;
    }

    /** java.security.Principal 的标准名称就是已验证 userId。 */
    @Override
    public String getName() {
        return userId;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role.trim().toUpperCase(Locale.ROOT));
    }

    private static LinkedHashSet<String> normalizeRoles(Set<String> roles) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (roles == null) {
            return normalized;
        }
        for (String role : roles) {
            if (role != null && !role.isBlank()) {
                normalized.add(role.trim().toUpperCase(Locale.ROOT));
            }
        }
        return normalized;
    }
}
