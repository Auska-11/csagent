package com.smartcs.smart_cs_agent.channel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闲鱼侧车会话注册端点集成测试（X2，启用态）。
 *
 * <p>覆盖：服务令牌三态（缺失/错误/正确）、buyerId/itemId 校验、幂等映射稳定性、
 * 签发的买家 JWT 能通过真实 SecurityFilterChain 调 /api/chat/send（走订单查询模板链路，
 * 不触网、不依赖 LLM）。关闭态的 404 行为由 {@link XianyuSidecarDisabledIntegrationTest} 覆盖。</p>
 */
@SpringBootTest(properties = {
        "smartcs.xianyu.enabled=true",
        "smartcs.xianyu.service-token=test-sidecar-service-token-32-bytes-long!!",
        "smartcs.xianyu.buyer-token-ttl-seconds=86400"
})
@AutoConfigureMockMvc
class XianyuSidecarControllerIntegrationTest {
    @org.junit.jupiter.api.io.TempDir static Path directory;
    @org.springframework.test.context.DynamicPropertySource
    static void properties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("smartcs.xianyu.item-snapshot-dir", () -> directory.toString());
    }


    private static final String SERVICE_TOKEN = "test-sidecar-service-token-32-bytes-long!!";
    private static final Pattern USER_ID_PATTERN = Pattern.compile("USER-XY-[0-9a-f]{8}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerShouldRejectMissingWrongAndAcceptCorrectServiceToken() throws Exception {
        String body = """
                {"buyerId":"221000111","itemId":"987654321"}
                """;

        mockMvc.perform(post("/api/xianyu/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post("/api/xianyu/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));

        mockMvc.perform(post("/api/xianyu/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.userId", matchesPattern("USER-XY-[0-9a-f]{8}")))
                .andExpect(jsonPath("$.conversationId").value("xianyu_221000111_987654321"))
                .andExpect(jsonPath("$.tenantId").value("xianyu"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNumber())
                .andExpect(jsonPath("$.itemKnowledge").value("none"));
    }

    @Test
    void buyerMappingShouldBeStableAndNotLeakPlatformId() throws Exception {
        String first = register("221000111", "987654321").get("userId").asText();
        String sameBuyerOtherItem = register("221000111", "111222333").get("userId").asText();
        String otherBuyer = register("221000112", "987654321").get("userId").asText();

        // 同一 buyerId 跨商品得到同一合成用户；不同 buyerId 得到不同合成用户。
        assertEquals(first, sameBuyerOtherItem);
        assertNotEquals(first, otherBuyer);
        // 合成用户不携带平台 ID 明文。
        assertFalse(first.contains("221000111"));
        assertTrue(USER_ID_PATTERN.matcher(first).matches());
    }

    @Test
    void buyerTokenShouldPassSecurityFilterChainAndChat() throws Exception {
        JsonNode session = register("221000555", "987654321");
        String token = session.get("token").asText();
        String conversationId = session.get("conversationId").asText();

        // 买家 JWT 走真实解码链路调用 /api/chat/send；订单查询是模板链路，不依赖 LLM。
        // 闲鱼租户没有种子订单，预期“未找到订单”式引导回复，重点是身份被接受而不是 403。
        mockMvc.perform(post("/api/chat/send")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"%s","message":"查询订单 ORD-002"}
                                """.formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId))
                .andExpect(jsonPath("$.reply").isNotEmpty());
    }

    @Test
    void invalidIdsShouldBeRejectedAsBadRequest() throws Exception {
        assertBadRequest("""
                {"buyerId":"../etc","itemId":"987654321"}
                """);
        assertBadRequest("""
                {"buyerId":"","itemId":"987654321"}
                """);
        assertBadRequest("""
                {"buyerId":"221000111"}
                """);
    }

    @Test
    void itemSnapshotShouldCreateDraftExactlyOnce() throws Exception {
        String itemId = "987654999";
        // target/ 下的草稿可能来自上一次测试运行，先清掉保证“首次创建”语义可重复验证。
        Files.deleteIfExists(directory.resolve("profiles/" + itemId + ".json"));
        String snapshot = """
                {"title":"蓝牙耳机 Pro Max","desc":"支持主动降噪","price_range":"¥39 - ¥59",\
                "total_stock":8,"sku_details":[{"spec":"黑色","price":39,"stock":5},{"spec":"白色","price":59,"stock":3}]}
                """;

        mockMvc.perform(post("/api/xianyu/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN)
                        .content("""
                                {"buyerId":"221000777","itemId":"%s","itemSnapshot":%s}
                                """.formatted(itemId, JSON.valueToTree(snapshot).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemKnowledge").value("created"));

        // 同一商品的第二个买家：草稿已存在，不覆盖（existing）。
        mockMvc.perform(post("/api/xianyu/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN)
                        .content("""
                                {"buyerId":"221000888","itemId":"%s","itemSnapshot":%s}
                                """.formatted(itemId, JSON.valueToTree(snapshot).toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemKnowledge").value("existing"));

        Path draft = directory.resolve("profiles/" + itemId + ".json");
        assertTrue(Files.exists(draft), "草稿文件应存在: " + draft);
        JsonNode saved = JSON.readTree(Files.readString(draft));
        assertEquals("蓝牙耳机 Pro Max", saved.path("draft").path("title").asText());
        assertEquals("支持主动降噪", saved.path("draft").path("description").asText());
        assertTrue(saved.path("published").isNull(), "自动快照只能生成审核草稿");
    }

    private JsonNode register(String buyerId, String itemId) throws Exception {
        String body = mockMvc.perform(post("/api/xianyu/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN)
                        .content("""
                                {"buyerId":"%s","itemId":"%s"}
                                """.formatted(buyerId, itemId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private void assertBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/xianyu/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
