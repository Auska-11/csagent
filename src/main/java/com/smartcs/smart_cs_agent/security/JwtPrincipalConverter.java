package com.smartcs.smart_cs_agent.security;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** 把已经通过签名校验的 JWT claim 转成 Agent 只认识的 AuthenticatedPrincipal。 */
public final class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenant_id");
        Set<String> roles = roles(jwt.getClaims().get("roles"));
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.authenticated(
                tenantId,
                jwt.getSubject(),
                roles
        );
        var authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .toList();

        // credentials 保存 Jwt 只供安全基础设施审计；业务 Controller 只接收 principal。
        return new UsernamePasswordAuthenticationToken(principal, jwt, authorities);
    }

    private Set<String> roles(Object rawRoles) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (rawRoles instanceof Collection<?> collection) {
            collection.forEach(item -> addRole(result, item));
        } else if (rawRoles != null) {
            Arrays.stream(rawRoles.toString().split("[,\\s]+"))
                    .forEach(item -> addRole(result, item));
        }
        return Set.copyOf(result);
    }

    private void addRole(Set<String> target, Object value) {
        if (value != null && !value.toString().isBlank()) {
            target.add(value.toString().trim().toUpperCase(java.util.Locale.ROOT));
        }
    }

}
