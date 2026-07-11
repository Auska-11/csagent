package com.smartcs.smart_cs_agent.agent.context;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 服务端确认的调用者身份。
 *
 * <p>最终架构中，私有 Capability 只能信任这里的 userId，不能信任 LLM 或普通工具参数里的
 * {@code user_id}。当前项目尚未接入完整认证，因此提供 {@link #unverifiedClaim(String)} 作为迁移标记；
 * 它明确表示“客户端声称自己是谁，但服务端尚未验证”，不能被误当成已认证身份。</p>
 */
public final class AuthenticatedPrincipal {

    private final String userId;
    private final boolean authenticated;
    private final Set<String> roles;

    private AuthenticatedPrincipal(String userId, boolean authenticated, Set<String> roles) {
        this.userId = userId == null ? "" : userId.trim();
        this.authenticated = authenticated;
        this.roles = Collections.unmodifiableSet(new LinkedHashSet<>(roles == null ? Set.of() : roles));
    }

    public static AuthenticatedPrincipal anonymous() {
        return new AuthenticatedPrincipal("", false, Set.of());
    }

    public static AuthenticatedPrincipal unverifiedClaim(String userId) {
        return new AuthenticatedPrincipal(userId, false, Set.of());
    }

    public static AuthenticatedPrincipal authenticated(String userId, Set<String> roles) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Authenticated user id cannot be empty");
        }
        return new AuthenticatedPrincipal(userId, true, roles);
    }

    public String getUserId() {
        return userId;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public boolean hasRole(String role) {
        return role != null && roles.contains(role);
    }
}
