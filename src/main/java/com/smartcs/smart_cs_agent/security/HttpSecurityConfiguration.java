package com.smartcs.smart_cs_agent.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** HTTP 端点的统一认证与角色矩阵。 */
@Configuration
public class HttpSecurityConfiguration {

    @Bean
    public SecretKey smartCsJwtSecretKey(SecurityProperties properties) {
        return new SecretKeySpec(
                properties.getJwtSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey smartCsJwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(smartCsJwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey smartCsJwtSecretKey, SecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(smartCsJwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        JwtTimestampValidator timestamp = new JwtTimestampValidator(
                Duration.ofSeconds(properties.getClockSkewSeconds())
        );
        OAuth2TokenValidator<Jwt> issuer = new JwtIssuerValidator(properties.getIssuer());
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.getAudience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "JWT audience is not allowed",
                        null
                ));
        OAuth2TokenValidator<Jwt> identityClaims = jwt -> {
            if (jwt.getExpiresAt() == null) {
                return invalidToken("JWT expiration is required");
            }
            if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
                return invalidToken("JWT subject is required");
            }
            Object tenantId = jwt.getClaims().get("tenant_id");
            if (tenantId == null || tenantId.toString().isBlank()) {
                return invalidToken("JWT tenant_id is required");
            }
            return OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamp,
                issuer,
                audience,
                identityClaims
        ));
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityProperties properties,
            SecurityErrorWriter errorWriter) throws Exception {
        JwtPrincipalConverter principalConverter = new JwtPrincipalConverter();

        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/app.css",
                                "/assets/**",
                                "/favicon.ico",
                                "/error",
                                "/api/health",
                                "/actuator/health/**",
                                "/api/auth/demo/**"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/monitor/feedback")
                        .hasAnyRole(SecurityRoles.CUSTOMER, SecurityRoles.ADMIN)
                        .requestMatchers("/api/chat/**")
                        .hasAnyRole(SecurityRoles.CUSTOMER, SecurityRoles.ADMIN)
                        .requestMatchers(
                                "/api/admin/pending-handoffs",
                                "/api/admin/pending-handoffs/**"
                        ).hasRole(SecurityRoles.ADMIN)
                        .requestMatchers("/api/monitor/**", "/api/eval/**", "/api/tools/**", "/api/demo/**")
                        .hasRole(SecurityRoles.ADMIN)
                        .requestMatchers("/api/mcp/**", "/mcp")
                        .hasAnyRole(SecurityRoles.MCP_CLIENT, SecurityRoles.ADMIN)
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.writeUnauthorized(request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                errorWriter.writeForbidden(response))
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(principalConverter))
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.writeUnauthorized(request, response))
                        .accessDeniedHandler((request, response, exception) ->
                                errorWriter.writeForbidden(response))
                );
        return http.build();
    }

    private OAuth2TokenValidatorResult invalidToken(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                description,
                null
        ));
    }
}
