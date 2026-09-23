package com.smartcs.smart_cs_agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartcs.smart_cs_agent.handoff.sla.SlaV1Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SLA v1 服务账号登录：POST /api/auth/login 换取短期 JWT 并缓存。
 *
 * <p>契约要求：令牌到期重新登录；401 后由调用方刷新重试一次。这里只负责“取一个当前可用
 * 的令牌”与“作废旧令牌”，不负责调用业务接口。密码只进请求体，绝不进日志与异常消息。</p>
 */
@Component
@ConditionalOnProperty(name = "smartcs.handoff.mode", havingValue = "sla-v1")
public class SlaV1AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(SlaV1AuthTokenService.class);

    private final SlaV1Properties properties;
    private final RestClient restClient;
    private final Object loginLock = new Object();

    private volatile CachedToken cachedToken;

    // 多构造函数时必须显式指定装配入口，否则 Spring 回退找无参构造导致启动失败。
    @Autowired
    public SlaV1AuthTokenService(SlaV1Properties properties) {
        this.properties = properties;
        this.restClient = BoundedRestClientFactory.create(
                normalizeBaseUrl(properties.getBaseUrl()),
                properties.getConnectTimeoutMs(),
                properties.getReadTimeoutMs()
        );
    }

    /** 单元测试构造函数：Builder 可能已绑定 MockRestServiceServer，不能再覆盖超时设置。 */
    SlaV1AuthTokenService(SlaV1Properties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(normalizeBaseUrl(properties.getBaseUrl()))
                .build();
    }

    /**
     * 返回当前可用的访问令牌；过期时重新登录（登录本身互斥，避免并发重复登录）。
     *
     * @throws IllegalStateException 凭据缺失或登录失败（不包含密码或响应原文）
     */
    public String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.isValid(nowMs(), skewMs())) {
            return current.accessToken;
        }
        synchronized (loginLock) {
            current = cachedToken;
            if (current != null && current.isValid(nowMs(), skewMs())) {
                return current.accessToken;
            }
            login();
            return cachedToken.accessToken;
        }
    }

    /** API 调用返回 401 后作废缓存，下一次 accessToken() 强制重新登录。 */
    public void invalidate() {
        cachedToken = null;
    }

    private void login() {
        String username = properties.getUsername();
        String password = properties.getPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "SLA v1 service account credentials are not configured"
            );
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        LoginApiResponse response;
        try {
            response = restClient.post()
                    .uri("/api/auth/login")
                    .body(body)
                    .retrieve()
                    .body(LoginApiResponse.class);
        } catch (RestClientException e) {
            // 只暴露异常类型，不透传可能包含凭据回显的响应原文。
            log.warn("SLA v1 service account login failed: exceptionType={}",
                    e.getClass().getSimpleName());
            throw new IllegalStateException("SLA v1 service account login failed", e);
        }
        if (response == null || !response.success() || response.data() == null
                || response.data().accessToken() == null
                || response.data().accessToken().isBlank()
                || response.data().expiresAt() == null) {
            throw new IllegalStateException("SLA v1 service account login returned no usable token");
        }
        cachedToken = new CachedToken(
                response.data().accessToken(),
                response.data().expiresAt().toEpochMilli()
        );
    }

    private long nowMs() {
        return System.currentTimeMillis();
    }

    private long skewMs() {
        return properties.getTokenExpirySkewSeconds() * 1000L;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8081";
        }
        return baseUrl.replaceAll("/+$", "");
    }

    private static final class CachedToken {
        private final String accessToken;
        private final long expiresAtEpochMs;

        private CachedToken(String accessToken, long expiresAtEpochMs) {
            this.accessToken = Objects.requireNonNull(accessToken);
            this.expiresAtEpochMs = expiresAtEpochMs;
        }

        private boolean isValid(long nowEpochMs, long skewMs) {
            return nowEpochMs < expiresAtEpochMs - skewMs;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LoginApiResponse(boolean success, LoginPayload data, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LoginPayload(String tokenType, String accessToken, Instant expiresAt) {
    }
}
