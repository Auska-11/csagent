package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.escalation.EscalationWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EscalationWorkflow 的原因分类、摘要和政策问题负向边界测试。 */
class EscalationWorkflowTest {

    private EscalationWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new EscalationWorkflow(new GlobalInterruptPolicy());
    }

    @Test
    void shouldClassifyHumanComplaintAndDisputeSignals() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("我要找人工客服", GlobalInterruptPolicy.REASON_USER_REQUESTED_HUMAN);
        cases.put("我了解投诉规则了，现在请帮我转人工", GlobalInterruptPolicy.REASON_USER_REQUESTED_HUMAN);
        cases.put("我要投诉你们的服务", GlobalInterruptPolicy.REASON_COMPLAINT);
        cases.put("我要赔偿并向 12315 维权", GlobalInterruptPolicy.REASON_COMPENSATION_OR_DISPUTE);

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            TurnContext context = turn(entry.getKey());
            HandoffAction action = assertInstanceOf(
                    HandoffAction.class,
                    workflow.nextAction(context).getAction()
            );

            assertEquals(entry.getValue(), action.getReason());
            assertTrue(action.getSummary().contains(entry.getKey()));
        }
    }

    @Test
    void shouldCarryExistingTaskCluesIntoHumanSummary() {
        String message = "这个订单一直没解决，我要投诉";
        ConversationState state = startedState(message);
        state.setActiveIntent("order_query");
        state.setTaskState("order_id", "ORD-003");

        HandoffAction action = assertInstanceOf(
                HandoffAction.class,
                workflow.nextAction(new TurnContext(request(message), state)).getAction()
        );

        assertEquals("order_query", action.getContext().get("previous_intent"));
        assertEquals("ORD-003", action.getContext().get("order_id"));
        assertTrue(action.getSummary().contains("订单号：ORD-003"));
    }

    @Test
    void shouldNotCreateTicketForHandoffPolicyQuestion() {
        String message = "什么情况可以转人工？";
        ConversationState state = startedState(message);

        assertEquals(
                0.0,
                workflow.match(new RoutingContext(request(message), state)).getScore()
        );
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(new TurnContext(request(message), state)).getAction()
        );
        assertTrue(answer.getAnswer().contains("没有确认需要创建人工工单"));
    }

    private TurnContext turn(String message) {
        ConversationState state = startedState(message);
        return new TurnContext(request(message), state);
    }

    private ConversationState startedState(String message) {
        ConversationState state = new ConversationState(message, "USER-001");
        state.startOrResumeWorkflow(EscalationWorkflow.ID, EscalationWorkflow.VERSION);
        return state;
    }

    private ChatRequest request(String message) {
        return new ChatRequest("conv-escalation-unit", "USER-001", message);
    }
}
