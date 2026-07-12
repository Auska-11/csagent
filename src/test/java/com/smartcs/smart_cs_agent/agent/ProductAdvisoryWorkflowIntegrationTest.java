package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 从真实客服入口验证商品 Workflow、长期偏好、Gateway 和 ProductRepository 的完整链路。 */
@SpringBootTest
class ProductAdvisoryWorkflowIntegrationTest {

    @Autowired
    private CustomerServiceAgent agent;

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private UserMemoryStore userMemoryStore;

    @Test
    void shouldAnswerPriceStockAndDescriptionFromRealCatalog() {
        String conversationId = "conv-product-advisory-integration";
        conversationStore.clear(conversationId);

        ChatResponse response = agent.chatWithTrace(request(
                conversationId,
                "USER-PRODUCT-A",
                "蓝牙耳机多少钱，有货吗"
        ));

        assertTrue(response.getReply().contains("蓝牙耳机 Pro Max"));
        assertTrue(response.getReply().contains("599 元"));
        assertTrue(response.getReply().contains("100 件"));
        assertTrue(response.getReply().contains("主动降噪"));
        assertTrue(hasTraceValue(response, "workflow_routed", "selectedWorkflow", "product_advisory"));
        assertTrue(hasTraceValue(response, "tool_observed", "capability", "product.search"));
    }

    @Test
    void shouldAskForProductThenResumeWorkflow() {
        String conversationId = "conv-product-keyword-integration";
        conversationStore.clear(conversationId);

        ChatResponse first = agent.chatWithTrace(request(
                conversationId,
                "USER-PRODUCT-B",
                "这个多少钱"
        ));
        assertTrue(first.getReply().contains("商品或品类"));

        ChatResponse second = agent.chatWithTrace(request(
                conversationId,
                "USER-PRODUCT-B",
                "蓝牙耳机"
        ));
        assertTrue(second.getReply().contains("蓝牙耳机 Pro Max"));
        assertTrue(second.getTrace().stream().anyMatch(event ->
                "workflow_routed".equals(event.getEventType())
                        && Boolean.TRUE.equals(event.getData().get("resumed"))
        ));
    }

    @Test
    void shouldPreserveLegacyPreferenceWriteThenUseItInNewWorkflow() {
        String conversationId = "conv-product-preference-integration";
        String userId = "USER-PRODUCT-C";
        conversationStore.clear(conversationId);
        userMemoryStore.clear(userId);

        // “我喜欢...”仍由 Legacy 用户记忆分支写入，商品 Workflow 主动不抢这类消息。
        ChatResponse remembered = agent.chatWithTrace(request(
                conversationId,
                userId,
                "我喜欢蓝牙耳机"
        ));
        assertTrue(remembered.getReply().contains("记住了"));

        ChatResponse recommendation = agent.chatWithTrace(request(
                conversationId,
                userId,
                "给我推荐一下"
        ));
        assertTrue(recommendation.getReply().contains("之前偏好的“蓝牙耳机”"));
        assertTrue(recommendation.getReply().contains("蓝牙耳机 Pro Max"));
        assertTrue(recommendation.getTrace().stream()
                .anyMatch(event -> "user_memory_used".equals(event.getEventType())));
    }

    private ChatRequest request(String conversationId, String userId, String message) {
        return new ChatRequest(conversationId, userId, message);
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
