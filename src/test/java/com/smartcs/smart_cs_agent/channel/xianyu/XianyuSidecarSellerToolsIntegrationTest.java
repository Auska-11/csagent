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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闲鱼侧车“卖家侧协作端点”HTTP 集成测试：本轮修复的对外契约层。
 *
 * <p>与 Runtime 级测试（XianyuBargainRuntimeIntegrationTest）的分工：那边用纯 Java
 * 装配验证 Handler/Runtime 内部行为，这里走真实 Spring 上下文 + SecurityFilterChain
 * + JSON 序列化，验证侧车进程实际看到的东西：</p>
 * <ul>
 *     <li>商品事实热更新（缺陷 #7）：改价/售出即时进入覆盖层，部分推送合并，
 *         非法输入 400 而不是猜值。</li>
 *     <li>人工模式闭环（缺陷 #1）：买家触发 pauseOnly 转人工 → handoffRequested=true
 *         → 会话自动暂停 → 后续买家消息 409 → 卖家消息（可选恢复）重新放行。
 *         工单创建、恢复机器人、发卖家消息是三件独立的事。</li>
 *     <li>无货不成交（缺陷 #3）：热更新库存 0 后买家议价收到“已售出”终态，
 *         不报价、不转人工。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "smartcs.xianyu.enabled=true",
        "smartcs.xianyu.service-token=test-sidecar-service-token-32-bytes-long!!",
        "smartcs.xianyu.item-snapshot-dir=target/xianyu-item-drafts-test"
})
@AutoConfigureMockMvc
class XianyuSidecarSellerToolsIntegrationTest {

    private static final String SERVICE_TOKEN = "test-sidecar-service-token-32-bytes-long!!";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    // --- 商品事实热更新（缺陷 #7） ---

    @Test
    void itemFactsHotUpdateValidatesInputMergesPartialPushesAndEchoesOverride() throws Exception {
        // 全量推送：价格 + 库存。
        postItemFacts("987612345", """
                {"price":"245","stock":1}
                """)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.itemId").value("987612345"))
                .andExpect(jsonPath("$.price").value("¥245"))
                .andExpect(jsonPath("$.stock").value(1));

        // 部分推送只推库存：价格沿用上一次覆盖值（合并，不是清空）。
        postItemFacts("987612345", """
                {"stock":0}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value("¥245"))
                .andExpect(jsonPath("$.stock").value(0));

        // 查询端点对账同一覆盖层。
        mockMvc.perform(get("/api/xianyu/items/987612345/facts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value("987612345"))
                .andExpect(jsonPath("$.price").value("¥245"))
                .andExpect(jsonPath("$.stock").value(0));

        // 区间价格按区间保留。
        postItemFacts("987612346", """
                {"price":"265-280"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value("¥265 - ¥280"));

        // 非法输入 400：价格不合法、负库存、空推送、非法 itemId。
        postItemFacts("987612347", """
                {"price":"面议"}
                """).andExpect(status().isBadRequest());
        postItemFacts("987612347", """
                {"stock":-1}
                """).andExpect(status().isBadRequest());
        postItemFacts("987612347", """
                {}
                """).andExpect(status().isBadRequest());
        postItemFacts("../etc", """
                {"price":"1"}
                """).andExpect(status().isBadRequest());

        // 服务令牌三态：缺失/错误 401，与 /sessions 一致。
        mockMvc.perform(post("/api/xianyu/items/987612345/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":\"1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/xianyu/items/987612345/facts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
                        .content("{\"price\":\"1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"));
    }

    @Test
    void itemSnapshotRegistrationSeedsLiveOverrideForImmediateUse() throws Exception {
        // 知识文档要走人工审校（慢），注册快照里的价格/库存即时进覆盖层，买家马上可议价。
        String snapshot = """
                {"title":"快照商品","price_range":"¥39 - ¥59","total_stock":8}
                """;
        mockMvc.perform(post("/api/xianyu/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN)
                        .content("""
                                {"buyerId":"221012999","itemId":"987612999","itemSnapshot":%s}
                                """.formatted(JSON.valueToTree(snapshot).toString())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/xianyu/items/987612999/facts")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value("987612999"))
                .andExpect(jsonPath("$.price").value("¥39 - ¥59"))
                .andExpect(jsonPath("$.stock").value(8));
    }

    // --- 人工模式端点 ---

    @Test
    void manualModeRoundTripRequiresExistingConversationAndExplicitFlag() throws Exception {
        // 从未聊过的 buyerId 没有会话状态：404 而不是静默成功。
        mockMvc.perform(get("/api/xianyu/conversations/manual-mode")
                        .queryParam("buyerId", "221019999")
                        .queryParam("itemId", "987619999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isNotFound());

        JsonNode session = register("221012345", "987612350");
        String conversationId = session.get("conversationId").asText();
        // 创建会话状态：走一Turn模板链路（不触网、不依赖 LLM）。
        sendBuyerMessage(session, "帮我查订单").andExpect(status().isOk());

        // 初始为自动模式。
        getManualMode("221012345", "987612350")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId))
                .andExpect(jsonPath("$.manualMode").value(false));

        // 暂停 → 查询一致。
        postManualMode("221012345", "987612350", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualMode").value(true));
        getManualMode("221012345", "987612350")
                .andExpect(jsonPath("$.manualMode").value(true));

        // 恢复 → 查询一致。
        postManualMode("221012345", "987612350", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualMode").value(false));
        getManualMode("221012345", "987612350")
                .andExpect(jsonPath("$.manualMode").value(false));

        // enabled 缺失 400；非法 ID 400。
        postManualMode("221012345", "987612350", null)
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/xianyu/conversations/manual-mode")
                        .queryParam("buyerId", "../etc")
                        .queryParam("itemId", "987612350")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN))
                .andExpect(status().isBadRequest());
    }

    // --- 卖家消息端点：发消息与恢复机器人是两件事 ---

    @Test
    void sellerMessageDeliveryIsSeparateFromBotResume() throws Exception {
        JsonNode session = register("221012346", "987612351");
        sendBuyerMessage(session, "帮我查订单").andExpect(status().isOk());
        postManualMode("221012346", "987612351", true).andExpect(status().isOk());

        // 只发消息不恢复：买家看到卖家原文，机器人仍暂停。
        postSellerMessage("221012346", "987612351", "240拿走，我已经改好价了", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("xianyu_221012346_987612351"))
                .andExpect(jsonPath("$.deliveredText").value("240拿走，我已经改好价了"))
                .andExpect(jsonPath("$.botResumed").value(false));
        getManualMode("221012346", "987612351")
                .andExpect(jsonPath("$.manualMode").value(true));

        // 发消息同时恢复：会话重新放行。
        postSellerMessage("221012346", "987612351", "您直接拍就行", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botResumed").value(true));
        getManualMode("221012346", "987612351")
                .andExpect(jsonPath("$.manualMode").value(false));

        // 校验：空文本 400、超长 400、未知会话 404。
        postSellerMessage("221012346", "987612351", "  ", true)
                .andExpect(status().isBadRequest());
        postSellerMessage("221012346", "987612351", "x".repeat(2001), true)
                .andExpect(status().isBadRequest());
        postSellerMessage("221019999", "987619999", "你好", true)
                .andExpect(status().isNotFound());
    }

    // --- 缺陷 #1 闭环：买家触发 pauseOnly 转人工 → 自动暂停 → 409 → 卖家恢复 ---

    @Test
    void buyerBargainWithoutFactsPausesBotBlocksNextMessageUntilSellerResumes() throws Exception {
        // 该 itemId 没有知识文档也没有热更新：事实缺失必须转卖家确认，而不是凭空报价。
        JsonNode session = register("221012347", "987612352");
        String conversationId = session.get("conversationId").asText();

        // 第一条议价：pauseOnly 转人工。话术引导卖家确认，且不创建工单（对比“我要人工”的工单链路）。
        sendBuyerMessage(session, "240卖吗")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", containsString("卖家本人确认")))
                .andExpect(jsonPath("$.handoffRequested").value(true))
                .andExpect(jsonPath("$.reply", not(containsString("工单号"))));

        // 转人工即刻暂停：下一条买家消息 409，不进 Agent、无副作用。
        getManualMode("221012347", "987612352")
                .andExpect(jsonPath("$.manualMode").value(true));
        mockMvc.perform(post("/api/chat/send")
                        .with(bearer(session.get("token").asText()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"%s","message":"在吗"}
                                """.formatted(conversationId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("XIANYU_CONVERSATION_PAUSED"));

        // 卖家消息（resumeBot=true）恢复机器人；买家消息重新被处理。
        // 事实仍然缺失，所以仍是转卖家确认的闭环——关键是 200 而不是 409。
        postSellerMessage("221012347", "987612352", "240拿走，我已经改好价了", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botResumed").value(true));
        sendBuyerMessage(session, "240卖吗")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", containsString("卖家本人确认")))
                .andExpect(jsonPath("$.handoffRequested").value(true));
    }

    // --- 缺陷 #3 闭环：热更新售出后不报价、不成交、不转人工 ---

    @Test
    void soldOutHotUpdateStopsDealWithoutHandoff() throws Exception {
        JsonNode session = register("221012348", "987612353");
        postItemFacts("987612353", """
                {"stock":0}
                """).andExpect(status().isOk());

        sendBuyerMessage(session, "240卖吗")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply", containsString("售出")))
                .andExpect(jsonPath("$.handoffRequested").value(false))
                .andExpect(jsonPath("$.reply", not(containsString("成交"))));

        // 机器人未被暂停：会话仍可继续（售出是终态回复，不是人工接管）。
        getManualMode("221012348", "987612353")
                .andExpect(jsonPath("$.manualMode").value(false));
    }

    // --- 构造辅助 ---

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

    private org.springframework.test.web.servlet.ResultActions sendBuyerMessage(
            JsonNode session, String message) throws Exception {
        String conversationId = session.get("conversationId").asText();
        return mockMvc.perform(post("/api/chat/send")
                .with(bearer(session.get("token").asText()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"conversationId":"%s","message":"%s"}
                        """.formatted(conversationId, message)));
    }

    private org.springframework.test.web.servlet.ResultActions postItemFacts(
            String itemId, String body) throws Exception {
        return mockMvc.perform(post("/api/xianyu/items/{itemId}/facts", itemId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN)
                .content(body));
    }

    private org.springframework.test.web.servlet.ResultActions getManualMode(
            String buyerId, String itemId) throws Exception {
        return mockMvc.perform(get("/api/xianyu/conversations/manual-mode")
                .queryParam("buyerId", buyerId)
                .queryParam("itemId", itemId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN));
    }

    private org.springframework.test.web.servlet.ResultActions postManualMode(
            String buyerId, String itemId, Boolean enabled) throws Exception {
        String enabledJson = enabled == null ? "null" : enabled.toString();
        return mockMvc.perform(post("/api/xianyu/conversations/manual-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN)
                .content("""
                        {"buyerId":"%s","itemId":"%s","enabled":%s}
                        """.formatted(buyerId, itemId, enabledJson)));
    }

    private org.springframework.test.web.servlet.ResultActions postSellerMessage(
            String buyerId, String itemId, String text, boolean resumeBot) throws Exception {
        return mockMvc.perform(post("/api/xianyu/conversations/seller-messages")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN)
                .content(JSON.writeValueAsString(java.util.Map.of(
                        "buyerId", buyerId,
                        "itemId", itemId,
                        "text", text,
                        "resumeBot", resumeBot
                ))));
    }

    private RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
