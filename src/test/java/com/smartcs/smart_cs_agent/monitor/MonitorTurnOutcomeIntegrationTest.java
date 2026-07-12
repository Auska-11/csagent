package com.smartcs.smart_cs_agent.monitor;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 使用真实 Router、Workflow、Capability 和 Trace 验证 Monitor 的最终分类。
 *
 * <p>TurnOutcomeClassifierTest 会直接构造 Trace，适合快速验证规则；本测试不伪造事件，
 * 因此还能防止生产链路以后改了事件名或字段，而 Monitor 单元测试仍然错误通过。</p>
 */
@SpringBootTest
class MonitorTurnOutcomeIntegrationTest {

    @Autowired
    private CustomerServiceAgent agent;

    @Autowired
    private ConversationStore conversationStore;

    @Test
    void shouldClassifyMissingOrderAsBusinessRejected() {
        String conversationId = "monitor-outcome-order-missing";
        clearConversation(conversationId);

        ChatResponse response = agent.chatWithTrace(request(conversationId, "查询订单 ORD-999"));
        MonitorSummary summary = record(response);

        assertEquals(1, summary.getBusinessRejectedTurnCount());
        assertLatestOutcome(summary, TurnOutcome.BUSINESS_REJECTED, "ORDER_NOT_FOUND");
    }

    @Test
    void shouldClassifyAnotherUsersOrderAsGuardrailRejected() {
        String conversationId = "monitor-outcome-order-owner";
        clearConversation(conversationId);

        ChatResponse response = agent.chatWithTrace(request(conversationId, "查询订单 ORD-003"));
        MonitorSummary summary = record(response);

        assertEquals(1, summary.getGuardrailRejectedTurnCount());
        assertLatestOutcome(summary, TurnOutcome.GUARDRAIL_REJECTED, "ORDER_OWNERSHIP_MISMATCH");
    }

    @Test
    void shouldClassifyCancellationInARealMultiTurnWorkflow() {
        String conversationId = "monitor-outcome-user-cancelled";
        clearConversation(conversationId);
        MonitorService monitorService = new MonitorService();

        // 第一轮启动订单查询并等待订单号；这是一轮正常完成的追问。
        ChatResponse waitingForOrderId = agent.chatWithTrace(request(conversationId, "帮我查订单"));
        monitorService.recordTurn(waitingForOrderId);

        // 第二轮沿用同一个 ConversationStore 状态，真实触发 Workflow 的 CANCELLED 终态。
        ChatResponse cancelled = agent.chatWithTrace(request(conversationId, "取消"));
        monitorService.recordTurn(cancelled);

        MonitorSummary summary = monitorService.getSummary();
        assertEquals(2, summary.getTotalTurns());
        assertEquals(1, summary.getSuccessfulTurnCount());
        assertEquals(1, summary.getUserCancelledTurnCount());
        assertLatestOutcome(summary, TurnOutcome.USER_CANCELLED, "USER_CANCELLED");
    }

    @Test
    void shouldKeepSuccessfulHandoffSeparateFromUserCancellation() {
        String conversationId = "monitor-outcome-handoff";
        clearConversation(conversationId);
        MonitorService monitorService = new MonitorService();

        ChatResponse waitingForOrderId = agent.chatWithTrace(request(conversationId, "我要退货"));
        monitorService.recordTurn(waitingForOrderId);

        // “转人工”会终止原 Workflow，但结束原因是 HANDED_OFF，不是用户放弃本次服务。
        ChatResponse handedOff = agent.chatWithTrace(request(conversationId, "我要人工"));
        monitorService.recordTurn(handedOff);

        MonitorSummary summary = monitorService.getSummary();
        assertEquals(2, summary.getSuccessfulTurnCount());
        assertEquals(0, summary.getUserCancelledTurnCount());
        assertLatestOutcome(summary, TurnOutcome.SUCCESS, "");
    }

    private MonitorSummary record(ChatResponse response) {
        // 每个测试使用独立 MonitorService，避免 Spring 单例累计指标让测试彼此污染。
        MonitorService monitorService = new MonitorService();
        monitorService.recordTurn(response);
        return monitorService.getSummary();
    }

    private void assertLatestOutcome(MonitorSummary summary,
                                     TurnOutcome expectedOutcome,
                                     String expectedErrorCode) {
        MonitorTurnSnapshot latest = summary.getRecentTurns().get(0);
        assertEquals(expectedOutcome, latest.getOutcome());
        assertEquals(expectedErrorCode, latest.getErrorCode());
    }

    private ChatRequest request(String conversationId, String message) {
        return new ChatRequest(
                conversationId,
                "USER-001",
                message,
                AuthenticatedPrincipal.authenticated("USER-001", Set.of("CUSTOMER"))
        );
    }

    private void clearConversation(String conversationId) {
        conversationStore.clear(ConversationKey.defaultTenant("USER-001", conversationId));
    }
}
