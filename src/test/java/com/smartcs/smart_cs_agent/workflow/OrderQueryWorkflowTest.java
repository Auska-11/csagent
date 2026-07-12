package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderListResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderSummary;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.orderquery.OrderQueryStep;
import com.smartcs.smart_cs_agent.workflow.orderquery.OrderQueryWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OrderQueryWorkflow 的纯状态机测试，不启动 Spring 或真实 Repository。 */
class OrderQueryWorkflowTest {

    private OrderQueryWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new OrderQueryWorkflow(new GlobalInterruptPolicy());
    }

    @Test
    void shouldKeepWaitingForOrderIdAcrossTurnsThenFormatResult() {
        ConversationState state = startedState("帮我查订单", "USER-001");

        WorkflowDecision first = workflow.nextAction(turn(state, "帮我查订单", "USER-001"));
        assertInstanceOf(AskUserAction.class, first.getAction());
        assertStep(state, OrderQueryStep.WAITING_ORDER_ID);

        state.startNewTurn("ORD-001");
        TurnContext secondTurn = turn(state, "ORD-001", "USER-001");
        CapabilityAction lookup = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(secondTurn).getAction()
        );
        assertEquals(CapabilityIds.ORDER_LOOKUP, lookup.getCapability());
        assertEquals("ORD-001", lookup.getArguments().get("order_id"));

        secondTurn.addObservation("capability_result", success(
                CapabilityIds.ORDER_LOOKUP,
                ownOrder("ORD-001")
        ));
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(secondTurn).getAction()
        );
        assertTrue(answer.getAnswer().contains("蓝牙耳机 Pro Max"));
        assertTrue(answer.getAnswer().contains("SF1234567890"));
        assertStep(state, OrderQueryStep.COMPLETED);
    }

    @Test
    void shouldListOrdersUsingServerIdentityWithoutUserArgument() {
        ConversationState state = startedState("查看我的订单", "USER-001");
        TurnContext turn = turn(state, "查看我的订单", "USER-001");

        CapabilityAction action = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(turn).getAction()
        );
        assertEquals(CapabilityIds.ORDER_LIST_BY_USER, action.getCapability());
        assertTrue(action.getArguments().isEmpty(), "Workflow 不应把 user_id 当成普通工具参数");

        turn.addObservation("capability_result", success(
                CapabilityIds.ORDER_LIST_BY_USER,
                new OrderListResult("USER-001", List.of(
                        ownOrder("ORD-001"),
                        new OrderSummary(
                                "ORD-002", "USER-001", "PROD-002", "智能手表 S1", "待发货", ""
                        )
                ))
        ));
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(turn).getAction()
        );
        assertTrue(answer.getAnswer().contains("共有 2 个订单"));
        assertTrue(answer.getAnswer().contains("ORD-002"));
    }

    @Test
    void shouldNotExposeAnotherUsersOrder() {
        ConversationState state = startedState("查订单 ORD-003", "USER-001");
        TurnContext turn = turn(state, "查订单 ORD-003", "USER-001");
        workflow.nextAction(turn);

        turn.addObservation("capability_result", rejected(
                CapabilityIds.ORDER_LOOKUP,
                "ORDER_OWNERSHIP_MISMATCH"
        ));
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(turn).getAction()
        );

        assertTrue(answer.getAnswer().contains("不属于当前登录用户"));
        assertTrue(!answer.getAnswer().contains("USB-C 数据线"));
    }

    @Test
    void shouldRequireAuthenticationBeforeCallingPrivateOrderCapability() {
        ConversationState state = new ConversationState("查订单 ORD-001", "USER-001");
        state.startOrResumeWorkflow(OrderQueryWorkflow.ID, OrderQueryWorkflow.VERSION);
        TurnContext turn = new TurnContext(
                new ChatRequest("conv-order", "USER-001", "查订单 ORD-001"),
                state
        );

        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(turn).getAction()
        );
        assertTrue(answer.getAnswer().contains("需要先登录"));
    }

    @Test
    void shouldHandoffInsteadOfTreatingHumanRequestAsOrderId() {
        ConversationState state = startedState("帮我查订单", "USER-001");
        workflow.nextAction(turn(state, "帮我查订单", "USER-001"));
        state.startNewTurn("我要人工客服");

        HandoffAction action = assertInstanceOf(
                HandoffAction.class,
                workflow.nextAction(turn(state, "我要人工客服", "USER-001")).getAction()
        );
        assertEquals("USER_REQUESTED_HUMAN", action.getReason());
    }

    private ConversationState startedState(String message, String userId) {
        ConversationState state = new ConversationState(message, userId);
        state.startOrResumeWorkflow(OrderQueryWorkflow.ID, OrderQueryWorkflow.VERSION);
        return state;
    }

    private TurnContext turn(ConversationState state, String message, String userId) {
        return new TurnContext(
                new ChatRequest(
                        "conv-order",
                        userId,
                        message,
                        AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER"))
                ),
                state
        );
    }

    private CapabilityObservation success(String capabilityId, Object data) {
        return new CapabilityObservation(
                capabilityId,
                CapabilityOutcome.SUCCESS,
                "ok",
                data,
                null,
                "test-provider",
                false
        );
    }

    private CapabilityObservation rejected(String capabilityId, String errorCode) {
        return new CapabilityObservation(
                capabilityId,
                CapabilityOutcome.BUSINESS_REJECTED,
                "rejected",
                null,
                errorCode,
                "test-provider",
                false
        );
    }

    private OrderSummary ownOrder(String orderId) {
        return new OrderSummary(
                orderId,
                "USER-001",
                "PROD-001",
                "蓝牙耳机 Pro Max",
                "已发货",
                "SF1234567890"
        );
    }

    private void assertStep(ConversationState state, OrderQueryStep expected) {
        assertEquals(
                expected.name(),
                state.getWorkflowState(OrderQueryWorkflow.ID, "step")
        );
    }
}
