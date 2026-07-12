package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 从真实客服入口验证政策 Workflow、知识检索 Provider 和 Markdown 知识库的完整链路。 */
@SpringBootTest
class PolicyQaWorkflowIntegrationTest {

    @Autowired
    private CustomerServiceAgent agent;

    @Autowired
    private ConversationStore conversationStore;

    @Test
    void shouldAnswerReturnInvoiceAndDeliveryPoliciesFromKnowledgeBase() {
        ChatResponse returnPolicy = chat("conv-policy-return", "超过七天还能退吗");
        ChatResponse invoicePolicy = chat("conv-policy-invoice", "发票什么时候能开");
        ChatResponse deliveryPolicy = chat("conv-policy-delivery", "偏远地区可以配送吗");

        assertTrue(returnPolicy.getReply().contains("退换货政策"));
        assertTrue(returnPolicy.getReply().contains("超过 7 天"));
        assertTrue(invoicePolicy.getReply().contains("发票政策"));
        assertTrue(invoicePolicy.getReply().contains("1 到 3 个工作日"));
        assertTrue(deliveryPolicy.getReply().contains("配送政策"));
        assertTrue(deliveryPolicy.getReply().contains("配送限制"));
        assertTrue(hasTraceValue(returnPolicy, "workflow_routed", "selectedWorkflow", "policy_qa"));
        assertTrue(hasTraceValue(invoicePolicy, "tool_observed", "capability", "knowledge.search"));
    }

    @Test
    void shouldKeepWarrantyQuestionOwnedByWarrantyWorkflow() {
        ChatResponse response = chat("conv-policy-warranty-boundary", "蓝牙耳机保修多久");

        assertTrue(hasTraceValue(response, "workflow_routed", "selectedWorkflow", "warranty_query"));
        assertTrue(response.getReply().contains("保修政策"));
    }

    @Test
    void shouldExplainHandoffRulesWithoutCreatingHandoffTicket() {
        ChatResponse response = chat("conv-policy-handoff-rules", "什么情况转人工");

        assertTrue(hasTraceValue(response, "workflow_routed", "selectedWorkflow", "policy_qa"));
        assertTrue(response.getReply().contains("人工转接政策"));
        assertTrue(response.getReply().contains("建议转接人工客服"));
    }

    @Test
    void shouldAskBroadTopicThenResumePolicyWorkflow() {
        String conversationId = "conv-policy-topic";
        conversationStore.clear(conversationId);

        ChatResponse first = agent.chatWithTrace(request(conversationId, "我想咨询政策"));
        assertTrue(first.getReply().contains("哪类政策"));

        ChatResponse second = agent.chatWithTrace(request(conversationId, "发票什么时候开"));
        assertTrue(second.getReply().contains("发票政策"));
        assertTrue(second.getTrace().stream().anyMatch(event ->
                "workflow_routed".equals(event.getEventType())
                        && Boolean.TRUE.equals(event.getData().get("resumed"))
        ));
    }

    private ChatResponse chat(String conversationId, String message) {
        conversationStore.clear(conversationId);
        return agent.chatWithTrace(request(conversationId, message));
    }

    private ChatRequest request(String conversationId, String message) {
        return new ChatRequest(conversationId, "USER-POLICY", message);
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
