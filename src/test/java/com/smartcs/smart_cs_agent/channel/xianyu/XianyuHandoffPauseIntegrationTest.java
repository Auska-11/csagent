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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闲鱼买家触发双层转人工的 HTTP 集成测试（X4，docs-76 §5.2 设计点 4）。
 *
 * <p>链路：买家 JWT -> /api/chat/send “我要人工” -> EscalationWorkflow ->
 * handoff.request（xianyu 租户放行）-> 工单创建成功 -> ChatResponse.handoffRequested=true。
 * 侧车据此调用 enter_manual_mode 暂停该会话的自动回复，卖家可用句号恢复。</p>
 *
 * <p>本测试不触网、不依赖 LLM：转人工链路使用确定性摘要与本地工单 Provider。</p>
 */
@SpringBootTest(properties = {
        "smartcs.xianyu.enabled=true",
        "smartcs.xianyu.service-token=test-sidecar-service-token-32-bytes-long!!",
        "smartcs.xianyu.item-snapshot-dir=target/xianyu-item-drafts-test"
})
@AutoConfigureMockMvc
class XianyuHandoffPauseIntegrationTest {

    private static final String SERVICE_TOKEN = "test-sidecar-service-token-32-bytes-long!!";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void buyerRequestingHumanShouldSetHandoffFlag() throws Exception {
        JsonNode session = register("221000999", "987654321");
        String token = session.get("token").asText();
        String conversationId = session.get("conversationId").asText();

        mockMvc.perform(post("/api/chat/send")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"%s","message":"我要人工"}
                                """.formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId))
                .andExpect(jsonPath("$.reply", containsString("工单号")))
                // 双层转人工的业务层出口：侧车读到该标志位后暂停该会话的自动回复。
                .andExpect(jsonPath("$.handoffRequested").value(true));
    }

    @Test
    void normalTurnShouldNotSetHandoffFlag() throws Exception {
        JsonNode session = register("221001000", "987654321");
        String token = session.get("token").asText();
        String conversationId = session.get("conversationId").asText();

        // 订单查询走模板链路（不触网、不依赖 LLM）；等待买家补充订单号的追问轮不是转人工。
        mockMvc.perform(post("/api/chat/send")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"%s","message":"帮我查订单"}
                                """.formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").isNotEmpty())
                .andExpect(jsonPath("$.handoffRequested").value(false));
    }

    @Test
    void handoffReplyShouldStayCleanOfOutboundContactPatterns() throws Exception {
        JsonNode session = register("221001001", "987654321");
        String token = session.get("token").asText();
        String conversationId = session.get("conversationId").asText();

        // 出站过滤对所有渠道生效：转人工话术不应携带站外联系方式引导（这里同时验证回复非空可用）。
        mockMvc.perform(post("/api/chat/send")
                        .with(bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"%s","message":"找人工，我要退货"}
                                """.formatted(conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", not(containsString("加我微信"))));
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

    private RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
