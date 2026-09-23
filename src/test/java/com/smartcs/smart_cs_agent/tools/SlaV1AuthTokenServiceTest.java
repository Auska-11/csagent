package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.handoff.sla.SlaV1Properties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 服务账号登录：凭据只进请求体、令牌缓存到 expiresAt（含提前量）、失效后重新登录。
 */
class SlaV1AuthTokenServiceTest {

    private SlaV1Properties properties() {
        SlaV1Properties properties = new SlaV1Properties();
        properties.setBaseUrl("http://sla-platform");
        properties.setUsername("agent-account");
        properties.setPassword("agent-password");
        return properties;
    }

    private static String loginBody(String accessToken, String expiresAt) {
        return """
                {
                  "success": true,
                  "data": {
                    "tokenType": "Bearer",
                    "accessToken": "%s",
                    "expiresAt": "%s"
                  },
                  "message": null
                }
                """.formatted(accessToken, expiresAt);
    }

    @Test
    void shouldLoginOnceAndCacheTokenUntilNearExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlaV1AuthTokenService service = new SlaV1AuthTokenService(properties(), builder);

        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.username").value("agent-account"))
                .andExpect(jsonPath("$.password").value("agent-password"))
                .andRespond(withSuccess(
                        loginBody("token-1", "2099-01-01T00:00:00Z"),
                        MediaType.APPLICATION_JSON
                ));

        assertEquals("token-1", service.accessToken());
        // 缓存有效期内直接复用：expect 恰好一次登录，若发生第二次请求 Mock 会立即失败。
        assertEquals("token-1", service.accessToken());
        server.verify();
    }

    @Test
    void shouldReLoginAfterInvalidation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SlaV1AuthTokenService service = new SlaV1AuthTokenService(properties(), builder);

        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andRespond(withSuccess(
                        loginBody("token-1", "2099-01-01T00:00:00Z"),
                        MediaType.APPLICATION_JSON
                ));
        server.expect(requestTo("http://sla-platform/api/auth/login"))
                .andRespond(withSuccess(
                        loginBody("token-2", "2099-01-01T00:00:00Z"),
                        MediaType.APPLICATION_JSON
                ));

        assertEquals("token-1", service.accessToken());
        // 401 后调用方作废缓存，下一次取令牌必须重新登录。
        service.invalidate();
        assertEquals("token-2", service.accessToken());
        server.verify();
    }

    @Test
    void shouldFailFastWhenCredentialsAreMissing() {
        RestClient.Builder builder = RestClient.builder();
        SlaV1Properties missing = new SlaV1Properties();
        missing.setBaseUrl("http://sla-platform");
        SlaV1AuthTokenService service = new SlaV1AuthTokenService(missing, builder);

        IllegalStateException error = assertThrows(IllegalStateException.class, service::accessToken);

        assertEquals("SLA v1 service account credentials are not configured", error.getMessage());
        // 异常消息不得携带凭据相关细节。
        assertTrue(error.getCause() == null || error.getCause().getMessage() == null
                || !error.getCause().getMessage().contains("password"));
    }
}
