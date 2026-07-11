package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.ConfirmAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.model.Order;
import com.smartcs.smart_cs_agent.model.Product;
import com.smartcs.smart_cs_agent.model.ReturnRecord;
import com.smartcs.smart_cs_agent.tools.OrderToolData;
import com.smartcs.smart_cs_agent.tools.ReturnToolData;
import com.smartcs.smart_cs_agent.tools.ToolResult;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.returnapply.ReturnApplyStep;
import com.smartcs.smart_cs_agent.workflow.returnapply.ReturnApplyWorkflow;
import com.smartcs.smart_cs_agent.workflow.returnapply.ReturnApplyWorkflowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 独立退货状态机测试，不启动 Spring、不访问真实 Repository。 */
class ReturnApplyWorkflowTest {

    private ReturnApplyWorkflow workflow;

    @BeforeEach
    void setUp() {
        ReturnApplyWorkflowProperties properties = new ReturnApplyWorkflowProperties();
        properties.setEnabled(true);
        workflow = new ReturnApplyWorkflow(new GlobalInterruptPolicy(), properties);
    }

    @Test
    void shouldCollectValidateConfirmAndOnlyThenSubmit() {
        ConversationState state = startedState("我要退货", "USER-002");

        WorkflowDecision first = workflow.nextAction(turn(state, "我要退货", "USER-002"));
        assertInstanceOf(AskUserAction.class, first.getAction());
        assertStep(state, ReturnApplyStep.WAITING_ORDER_ID);

        state.startNewTurn("ORD-003");
        TurnContext orderTurn = turn(state, "ORD-003", "USER-002");
        WorkflowDecision orderDecision = workflow.nextAction(orderTurn);
        CapabilityAction orderLookup = assertInstanceOf(CapabilityAction.class, orderDecision.getAction());
        assertEquals("order.lookup", orderLookup.getCapability());

        orderTurn.addObservation("capability", ToolResult.success(
                "order.lookup",
                "found",
                ownedOrder("USER-002")
        ));
        WorkflowDecision reasonDecision = workflow.nextAction(orderTurn);
        assertInstanceOf(AskUserAction.class, reasonDecision.getAction());
        assertStep(state, ReturnApplyStep.WAITING_REASON);

        state.startNewTurn("质量问题");
        WorkflowDecision confirmationDecision = workflow.nextAction(turn(state, "质量问题", "USER-002"));
        ConfirmAction confirmation = assertInstanceOf(ConfirmAction.class, confirmationDecision.getAction());
        assertEquals("return.apply", confirmation.getOperation());
        assertStep(state, ReturnApplyStep.WAITING_CONFIRMATION);

        // 模糊回答不得被当成确认，也不能产生 CapabilityAction。
        state.startNewTurn("我再想想");
        WorkflowDecision unclear = workflow.nextAction(turn(state, "我再想想", "USER-002"));
        assertInstanceOf(ConfirmAction.class, unclear.getAction());

        state.startNewTurn("确认提交");
        TurnContext submitTurn = turn(state, "确认提交", "USER-002");
        CapabilityAction submit = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(submitTurn).getAction()
        );
        assertEquals("return.apply", submit.getCapability());
        assertEquals("ORD-003", submit.getArguments().get("order_id"));
        assertEquals("质量问题", submit.getArguments().get("reason"));

        submitTurn.addObservation("capability", ToolResult.success(
                "return.apply",
                "submitted",
                ReturnToolData.submitted(
                        new ReturnRecord("RET-002", "ORD-003", "质量问题", "Under Review"),
                        "USB-C 数据线",
                        "1-2 business days"
                )
        ));
        FinalAnswerAction completed = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(submitTurn).getAction()
        );
        assertTrue(completed.getAnswer().contains("RET-002"));
        assertEquals(WorkflowStatus.COMPLETED, state.getWorkflowStatus());
    }

    @Test
    void shouldRejectOrderOwnedByAnotherUser() {
        ConversationState state = startedState("我要退货 ORD-003，质量问题", "USER-001");
        TurnContext context = turn(state, "我要退货 ORD-003，质量问题", "USER-001");

        assertInstanceOf(CapabilityAction.class, workflow.nextAction(context).getAction());
        context.addObservation("capability", ToolResult.success(
                "order.lookup",
                "found",
                ownedOrder("USER-002")
        ));

        FinalAnswerAction denied = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );
        assertTrue(denied.getAnswer().contains("不属于当前用户"));
        assertEquals(WorkflowStatus.CANCELLED, state.getWorkflowStatus());
    }

    @Test
    void shouldCancelWithoutCreatingCapabilityCallWhileWaitingConfirmation() {
        ConversationState state = stateWaitingForConfirmation();
        state.startNewTurn("取消");

        WorkflowDecision decision = workflow.nextAction(turn(state, "取消", "USER-002"));

        FinalAnswerAction answer = assertInstanceOf(FinalAnswerAction.class, decision.getAction());
        assertTrue(answer.getAnswer().contains("没有提交"));
        assertEquals(WorkflowStatus.CANCELLED, state.getWorkflowStatus());
    }

    @Test
    void shouldHandoffInsteadOfSavingHumanRequestAsReturnReason() {
        ConversationState state = startedState("我要退货", "USER-002");
        workflow.nextAction(turn(state, "我要退货", "USER-002"));
        state.startNewTurn("我要人工");

        WorkflowDecision decision = workflow.nextAction(turn(state, "我要人工", "USER-002"));

        assertInstanceOf(HandoffAction.class, decision.getAction());
        assertEquals(WorkflowStatus.CANCELLED, state.getWorkflowStatus());
    }

    private ConversationState stateWaitingForConfirmation() {
        ConversationState state = startedState("我要退货 ORD-003，质量问题", "USER-002");
        TurnContext context = turn(state, "我要退货 ORD-003，质量问题", "USER-002");
        workflow.nextAction(context);
        context.addObservation("capability", ToolResult.success(
                "order.lookup",
                "found",
                ownedOrder("USER-002")
        ));
        assertInstanceOf(ConfirmAction.class, workflow.nextAction(context).getAction());
        return state;
    }

    private ConversationState startedState(String message, String userId) {
        ConversationState state = new ConversationState(message, userId);
        state.startOrResumeWorkflow(ReturnApplyWorkflow.ID, ReturnApplyWorkflow.VERSION);
        return state;
    }

    private TurnContext turn(ConversationState state, String message, String userId) {
        return new TurnContext(new ChatRequest("conv-return", userId, message), state);
    }

    private OrderToolData ownedOrder(String ownerId) {
        return OrderToolData.found(
                new Order("ORD-003", ownerId, "PROD-003", "已签收", "YT9876543210"),
                new Product("PROD-003", "USB-C 数据线", 39, 20, "测试商品")
        );
    }

    private void assertStep(ConversationState state, ReturnApplyStep expected) {
        assertEquals(
                expected.name(),
                state.getWorkflowState(ReturnApplyWorkflow.ID, "step")
        );
    }
}
