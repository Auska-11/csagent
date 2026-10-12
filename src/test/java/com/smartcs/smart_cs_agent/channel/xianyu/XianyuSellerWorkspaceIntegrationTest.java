package com.smartcs.smart_cs_agent.channel.xianyu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real security chain, workflow routing, profile publication and inbox replay; no platform or model network. */
@SpringBootTest(properties = {"smartcs.xianyu.enabled=true", "smartcs.xianyu.service-token=test-sidecar-workspace-service-token-long"})
@AutoConfigureMockMvc
class XianyuSellerWorkspaceIntegrationTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("smartcs.xianyu.item-snapshot-dir", () -> directory.toString());
    }
    @Autowired MockMvc mvc;
    @Autowired JwtEncoder encoder;
    @Autowired SecurityProperties security;
    @Autowired ObjectMapper json;
    @Autowired XianyuItemFactService facts;
    @Autowired XianyuItemProfileStore profiles;
    @Autowired ConversationStore conversations;

    private String token(String tenant, String user, String role) {
        var now = Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), JwtClaimsSet.builder()
                .issuer(security.getIssuer()).audience(List.of(security.getAudience())).subject(user)
                .issuedAt(now).expiresAt(now.plusSeconds(300)).claim("tenant_id", tenant).claim("roles", Set.of(role)).build())).getTokenValue();
    }
    private String seller() { return token("xianyu", "seller", "ADMIN"); }
    private String buyer(String id) { return token("xianyu", XianyuIdentity.syntheticUserId(id), "CUSTOMER"); }
    private void publishItem(String id) {
        var profile = new XianyuItemProfileStore.ItemProfile("耳机", "headphones", "M2", "自用", "功能未测试", "耳机和线缆",
                new XianyuItemProfileStore.SellerPolicy(true, new java.math.BigDecimal("240"), null, "", "48 小时内发货"));
        profiles.saveDraft(id, 0, profile); profiles.publish(id, 1, "seller");
        facts.applyLiveOverride(id, XianyuItemFactService.PriceRange.of(new java.math.BigDecimal("265.50")), 1);
    }
    private JsonNode chat(String buyerId, String itemId, String eventId, String message) throws Exception {
        return json.readTree(mvc.perform(post("/api/chat/send").header("Authorization", "Bearer " + buyer(buyerId))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of(
                        "conversationId", XianyuIdentity.conversationId(buyerId, itemId), "messageId", eventId, "message", message))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void jwtScopeAndRoleProtectSellerDraftsAndPublishedAuthorization() throws Exception {
        mvc.perform(get("/api/admin/xianyu/items")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/xianyu/items").header("Authorization", "Bearer " + buyer("101"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/xianyu/items").header("Authorization", "Bearer " + token("other", "admin", "ADMIN"))).andExpect(status().isForbidden());
        String draft = """
                {"expectedVersion":0,"profile":{"title":"耳机","condition":"未测试","sellerPolicy":{"bargainAccepted":false}}}
                """;
        mvc.perform(put("/api/admin/xianyu/items/901/draft").header("Authorization", "Bearer " + seller()).contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.published").isEmpty());
        assertTrue(facts.itemFacts("901").isEmpty(), "Draft content must not enter buyer facts");
        mvc.perform(post("/api/admin/xianyu/items/901/publish").header("Authorization", "Bearer " + seller()).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewedBy").value("seller"));
        mvc.perform(put("/api/admin/xianyu/items/901/draft").header("Authorization", "Bearer " + seller()).contentType(MediaType.APPLICATION_JSON).content(draft))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("ITEM_PROFILE_VERSION_CONFLICT"));
        assertEquals("未测试", facts.itemFacts("901").orElseThrow().condition());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + seller()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.channel").value("xianyu")).andExpect(jsonPath("$.tenantId").value("xianyu"));
    }

    @Test void authorizedShippingAndAccessoriesUseXianyuWorkflowWithoutModelOrDemoOrders() throws Exception {
        publishItem("902");
        var shipping = chat("102", "902", "shipping", "什么时候发货？");
        assertTrue(shipping.path("reply").asText().contains("48 小时"));
        assertTrue(shipping.path("trace").toString().contains("xianyu_trade"));
        assertFalse(shipping.path("reply").asText().contains("ORD-"));
        assertTrue(chat("102", "902", "accessories", "包含什么配件？").path("reply").asText().contains("耳机和线缆"));
    }

    @Test void unsupportedReturnsPauseSellerAndDuplicateBuyerAndSellerEventsDoNotRepeatHistory() throws Exception {
        publishItem("903");
        var initial = chat("103", "903", "return", "申请退货怎么办？");
        assertTrue(initial.path("handoffRequested").asBoolean());
        assertTrue(initial.path("reply").asText().contains("卖家"));
        assertFalse(initial.path("reply").asText().contains("订单号"));
        assertEquals(initial, chat("103", "903", "return", "申请退货怎么办？"));
        var key = new ConversationKey("xianyu", XianyuIdentity.syntheticUserId("103"), XianyuIdentity.conversationId("103", "903"));
        int before = conversations.get(key).getTotalMessageCount();
        String sellerMessage = "{\"buyerId\":\"103\",\"itemId\":\"903\",\"text\":\"我来核对\",\"messageId\":\"seller-event\"}";
        for (int attempt = 0; attempt < 2; attempt++) mvc.perform(post("/api/xianyu/conversations/seller-messages")
                .header("Authorization", "Bearer test-sidecar-workspace-service-token-long").contentType(MediaType.APPLICATION_JSON).content(sellerMessage)).andExpect(status().isOk());
        assertEquals(before + 1, conversations.get(key).getTotalMessageCount());
        mvc.perform(post("/api/admin/xianyu/conversations/manual-mode").header("Authorization", "Bearer " + seller()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"buyerId\":\"103\",\"itemId\":\"903\",\"enabled\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.manualMode").value(false));
    }

    @Test void buyerCannotUseAnotherBuyersConversationOrAvoidChannelPolicyWithArbitraryConversationId() throws Exception {
        for (String conversation : new String[]{"xianyu_999_904", "default-admin", "xianyu_invalid"}) {
            mvc.perform(post("/api/chat/send").header("Authorization", "Bearer " + buyer("104")).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(java.util.Map.of("conversationId", conversation, "message", "查订单"))))
                    .andExpect(status().isForbidden());
        }
    }
}
