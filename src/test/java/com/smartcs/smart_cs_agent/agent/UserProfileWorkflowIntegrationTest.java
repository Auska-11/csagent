package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 从真实客服入口验证用户资料、认证 Principal、长期偏好和商品推荐的完整链路。 */
@SpringBootTest
class UserProfileWorkflowIntegrationTest {

    @Autowired
    private CustomerServiceAgent agent;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private UserMemoryStore userMemoryStore;

    @Test
    void shouldReturnOnlyAuthenticatedUsersMaskedProfile() {
        String conversationId = "conv-user-profile-integration";
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));

        ChatResponse response = agent.chatWithTrace(authenticatedRequest(
                conversationId,
                "USER-001",
                "查看我的个人信息"
        ));

        assertTrue(response.getReply().contains("张三"));
        assertTrue(response.getReply().contains("138****1234"));
        assertFalse(response.getReply().contains("13812341234"));
        assertFalse(response.getReply().contains("token-zhangsan"));
        assertTrue(hasTraceValue(response, "workflow_routed", "selectedWorkflow", "user_profile"));
        assertTrue(hasTraceValue(response, "tool_observed", "capability", "user.lookup"));
    }

    @Test
    void shouldRejectUnverifiedProfileRequestBeforeCapabilityCall() {
        String conversationId = "conv-user-profile-unverified";
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));

        ChatResponse response = agent.chatWithTrace(
                new ChatRequest(conversationId, "USER-001", "查看我的信息")
        );

        assertTrue(response.getReply().contains("需要先登录"));
        assertFalse(hasTraceValue(response, "tool_observed", "capability", "user.lookup"));
    }

    @Test
    void shouldWritePreferenceInUserWorkflowThenUseItForRecommendation() {
        String conversationId = "conv-user-preference-integration";
        String userId = "USER-002";
        conversationStore.clear(ConversationKey.defaultTenant(userId, conversationId));
        userMemoryStore.clear("default", userId);

        ChatResponse remembered = agent.chatWithTrace(authenticatedRequest(
                conversationId,
                userId,
                "我喜欢蓝牙耳机"
        ));
        assertTrue(remembered.getReply().contains("记住了"));
        assertTrue(hasTraceValue(remembered, "workflow_routed", "selectedWorkflow", "user_profile"));

        ChatResponse recommendation = agent.chatWithTrace(authenticatedRequest(
                conversationId,
                userId,
                "给我推荐一下"
        ));
        assertTrue(recommendation.getReply().contains("之前偏好的“蓝牙耳机”"));
        assertTrue(recommendation.getReply().contains("蓝牙耳机 Pro Max"));
    }

    @Test
    void profileWritesAndRecommendationsMustRemainWithinAuthenticatedTenant() {
        String userId = "tenant-preference-user";
        String conversationId = "shared-preference-conversation";
        for (String tenant : new String[]{"tenant-a", "tenant-b"}) {
            userMemoryStore.clear(tenant, userId);
            conversationStore.clear(new ConversationKey(tenant, userId, conversationId));
        }

        ChatResponse first = agent.chatWithTrace(tenantRequest(
                "tenant-a", conversationId, userId, "我喜欢蓝牙耳机"));
        ChatResponse second = agent.chatWithTrace(tenantRequest(
                "tenant-b", conversationId, userId, "我喜欢智能手表"));
        assertTrue(first.getReply().contains("记住了"));
        assertTrue(second.getReply().contains("记住了"));
        assertEquals(java.util.List.of("蓝牙耳机"),
                userMemoryStore.getSnapshot("tenant-a", userId).getProductPreferences());
        assertEquals(java.util.List.of("智能手表"),
                userMemoryStore.getSnapshot("tenant-b", userId).getProductPreferences());

        ChatResponse recommendationA = agent.chatWithTrace(tenantRequest(
                "tenant-a", conversationId, userId, "给我推荐一下"));
        ChatResponse recommendationB = agent.chatWithTrace(tenantRequest(
                "tenant-b", conversationId, userId, "给我推荐一下"));
        assertTrue(recommendationA.getReply().contains("之前偏好的“蓝牙耳机”"));
        assertFalse(recommendationA.getReply().contains("智能手表"));
        assertTrue(recommendationB.getReply().contains("之前偏好的“智能手表”"));
        assertFalse(recommendationB.getReply().contains("蓝牙耳机"));
    }

    private ChatRequest tenantRequest(String tenantId, String conversationId, String userId, String message) {
        return new ChatRequest(tenantId, conversationId, userId, message,
                AuthenticatedPrincipal.authenticated(tenantId, userId, Set.of("CUSTOMER")));
    }

    private ChatRequest authenticatedRequest(String conversationId, String userId, String message) {
        return new ChatRequest(
                conversationId,
                userId,
                message,
                AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER"))
        );
    }

    private boolean hasTraceValue(ChatResponse response,
                                  String eventType,
                                  String field,
                                  String expected) {
        return response.getTrace().stream().anyMatch(event ->
                eventType.equals(event.getEventType())
                        && expected.equals(event.getData().get(field))
        );
    }
}
