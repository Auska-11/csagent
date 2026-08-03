package com.smartcs.smart_cs_agent.security;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 SecurityFilterChain、JWT 签名校验和端点角色矩阵集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DemoJwtTokenService tokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private SecurityProperties properties;

    @Autowired
    private ConversationStore conversationStore;

    @Test
    void demoIssuerShouldReturnShortLivedTokensWithoutCaching() throws Exception {
        mockMvc.perform(post("/api/auth/demo/tokens"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$[0].userId").value("USER-001"))
                .andExpect(jsonPath("$[0].token").isNotEmpty())
                .andExpect(jsonPath("$[0].roles[?(@ == 'ADMIN')]").exists());
    }

    @Test
    void workbenchStylesheetShouldBePublicWithoutOpeningArbitraryStaticPaths() throws Exception {
        mockMvc.perform(get("/app.css"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/css"));

        mockMvc.perform(get("/not-allowlisted.css"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bodyTokenMustNotBypassBearerAuthentication() throws Exception {
        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message":"hello",
                                  "userId":"USER-001",
                                  "token":"legacy-body-token"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void customerCannotReadManagementDataButAdminCan() throws Exception {
        String customerToken = token("USER-002");
        String adminToken = token("USER-001");

        mockMvc.perform(get("/api/monitor/summary").with(bearer(customerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/tools").with(bearer(customerToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/monitor/summary").with(bearer(adminToken)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tools").with(bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(9));
    }

    @Test
    void pendingHandoffAdminApiShouldEnforceJwtRoleMatrix() throws Exception {
        String customerToken = token("USER-002");
        String adminToken = token("USER-001");
        String mcpToken = tokenService.issueMcpClientToken("mcp-review-client").token();

        mockMvc.perform(get("/api/admin/pending-handoffs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/admin/pending-handoffs").with(bearer(customerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/admin/pending-handoffs").with(bearer(mcpToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        // 这里走完整 JWT decoder、Principal converter、SecurityFilterChain、Service 和 Memory Store，
        // 能发现“Controller 单测放行了，但生产 anyRequest().denyAll() 仍拦住新 URL”这类装配错误。
        mockMvc.perform(get("/api/admin/pending-handoffs").with(bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").isBoolean());
    }

    @Test
    void wrongAudienceJwtShouldBeRejectedBeforeController() throws Exception {
        String wrongAudienceToken = encodeToken(
                "USER-001",
                List.of("another-api"),
                Set.of(SecurityRoles.ADMIN)
        );

        mockMvc.perform(get("/api/monitor/summary").with(bearer(wrongAudienceToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void wrongIssuerExpiredAndIncompleteJwtShouldBeRejected() throws Exception {
        Instant now = Instant.now();
        String wrongIssuerToken = encodeToken(
                "another-issuer",
                "USER-001",
                List.of(properties.getAudience()),
                Set.of(SecurityRoles.ADMIN),
                now,
                now.plusSeconds(60),
                true
        );
        String expiredToken = encodeToken(
                properties.getIssuer(),
                "USER-001",
                List.of(properties.getAudience()),
                Set.of(SecurityRoles.ADMIN),
                now.minusSeconds(120),
                now.minusSeconds(60),
                true
        );
        String missingTenantToken = encodeToken(
                properties.getIssuer(),
                "USER-001",
                List.of(properties.getAudience()),
                Set.of(SecurityRoles.ADMIN),
                now,
                now.plusSeconds(60),
                false
        );
        String missingSubjectToken = encodeToken(
                properties.getIssuer(),
                null,
                List.of(properties.getAudience()),
                Set.of(SecurityRoles.ADMIN),
                now,
                now.plusSeconds(60),
                true
        );
        String missingExpirationToken = encodeToken(
                properties.getIssuer(),
                "USER-001",
                List.of(properties.getAudience()),
                Set.of(SecurityRoles.ADMIN),
                now,
                null,
                true
        );

        for (String invalidToken : List.of(
                wrongIssuerToken,
                expiredToken,
                missingTenantToken,
                missingSubjectToken,
                missingExpirationToken)) {
            mockMvc.perform(get("/api/monitor/summary").with(bearer(invalidToken)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
        }
    }

    @Test
    void mcpHttpEndpointsShouldOnlyAllowMcpClientOrAdmin() throws Exception {
        String customerToken = token("USER-002");
        String adminToken = token("USER-001");
        String mcpToken = tokenService.issueMcpClientToken("mcp-client").token();

        mockMvc.perform(get("/api/mcp/tools"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/mcp/tools").with(bearer(customerToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/mcp/tools").with(bearer(mcpToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol").value("mcp-adapter-v2"));
        mockMvc.perform(get("/api/mcp/tools").with(bearer(adminToken)))
                .andExpect(status().isOk());

        // MCP_CLIENT 只是服务身份，不能访问客户记忆或管理员面板。
        mockMvc.perform(get("/api/chat/users/mcp-client/memory").with(bearer(mcpToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/monitor/summary").with(bearer(mcpToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void feedbackMustBelongToPrincipalConversationAndServerReply() throws Exception {
        String customerToken = token("USER-002");
        String conversationId = "security-feedback-conv";
        ConversationState state = new ConversationState("hello", "USER-002");
        state.addAssistantMessage("verified assistant reply");
        conversationStore.save(
                new ConversationKey("default", "USER-002", conversationId),
                state
        );

        mockMvc.perform(post("/api/monitor/feedback")
                        .with(bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedback(conversationId, "USER-001", "verified assistant reply")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/monitor/feedback")
                        .with(bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedback(conversationId, null, "forged reply")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/monitor/feedback")
                        .with(bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feedback(conversationId, null, "verified assistant reply")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feedback.userId").value("USER-002"))
                .andExpect(jsonPath("$.feedback.assistantReply").value("verified assistant reply"));
    }

    @Test
    void mcpServiceCannotTurnUnsignedMetadataIntoCustomerIdentity() throws Exception {
        String customerToken = token("USER-002");
        String mcpToken = tokenService.issueMcpClientToken("mcp-client").token();

        mockMvc.perform(post("/mcp")
                        .with(bearer(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/mcp")
                        .with(bearer(mcpToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":2,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"order.lookup",
                                    "arguments":{"order_id":"ORD-001"},
                                    "_meta":{"userId":"USER-001","tenantId":"default"}
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result._meta.errorCode").value("MCP_DELEGATION_REQUIRED"));

        // 服务身份仍可调用真正公开的只读能力，证明委托限制不是禁用全部 MCP tools/call。
        mockMvc.perform(post("/mcp")
                        .with(bearer(mcpToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":3,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"knowledge.search",
                                    "arguments":{"query":"return policy"}
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));

        // 即使 Capability 是公开的，普通 JSON 元数据也不能覆盖已验证的 service Principal。
        mockMvc.perform(post("/mcp")
                        .with(bearer(mcpToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc":"2.0",
                                  "id":4,
                                  "method":"tools/call",
                                  "params":{
                                    "name":"knowledge.search",
                                    "arguments":{"query":"return policy"},
                                    "_meta":{"userId":"USER-001","tenantId":"default"}
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result._meta.errorCode")
                        .value("MCP_IDENTITY_METADATA_REJECTED"));
    }

    private String token(String userId) {
        return tokenService.issueDemoUserTokens().stream()
                .filter(token -> userId.equals(token.userId()))
                .findFirst()
                .orElseThrow()
                .token();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private String encodeToken(String subject, List<String> audience, Set<String> roles) {
        Instant now = Instant.now();
        return encodeToken(
                properties.getIssuer(),
                subject,
                audience,
                roles,
                now,
                now.plusSeconds(60),
                true
        );
    }

    private String encodeToken(String issuer,
                               String subject,
                               List<String> audience,
                               Set<String> roles,
                               Instant issuedAt,
                               Instant expiresAt,
                               boolean includeTenant) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(audience)
                .issuedAt(issuedAt)
                .claim("roles", roles);
        if (expiresAt != null) {
            builder.expiresAt(expiresAt);
        }
        if (subject != null) {
            builder.subject(subject);
        }
        if (includeTenant) {
            builder.claim("tenant_id", "default");
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                builder.build()
        )).getTokenValue();
    }

    private String feedback(String conversationId, String userId, String assistantReply) {
        String userField = userId == null ? "" : ",\"userId\":\"" + userId + "\"";
        return "{\"conversationId\":\"" + conversationId + "\""
                + userField
                + ",\"rating\":\"HELPFUL\",\"reason\":\"test\",\"assistantReply\":\""
                + assistantReply + "\"}";
    }
}
