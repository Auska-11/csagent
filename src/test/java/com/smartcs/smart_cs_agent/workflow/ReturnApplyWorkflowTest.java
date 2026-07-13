package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.action.ConfirmAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderSummary;
import com.smartcs.smart_cs_agent.capability.api.contracts.ReturnApplicationSummary;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.returnapply.ReturnApplyStep;
import com.smartcs.smart_cs_agent.workflow.returnapply.ReturnApplyWorkflow;
import com.smartcs.smart_cs_agent.workflow.returnapply.ReturnApplyWorkflowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

        observeSuccess(orderTurn,
                "order.lookup",
                "found",
                ownedOrder("USER-002")
        );
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

        observeSuccess(submitTurn,
                "return.apply",
                "submitted",
                new ReturnApplicationSummary(
                        true,
                        "RET-002",
                        "ORD-003",
                        "USB-C 数据线",
                        "质量问题",
                        "Under Review",
                        "1-2 business days"
                )
        );
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
        observeSuccess(context,
                "order.lookup",
                "found",
                ownedOrder("USER-002")
        );

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

        CancelWorkflowAction answer = assertInstanceOf(CancelWorkflowAction.class, decision.getAction());
        assertTrue(answer.getAnswer().contains("没有提交"));
        assertEquals(ReturnApplyStep.CANCELLED.name(), state.getWorkflowState(ReturnApplyWorkflow.ID, "step"));
        // 单测只执行了 Workflow 决策；CANCELLED 生命周期状态要等 Handler + Runtime 执行 Action 后写入。
        assertEquals(WorkflowStatus.ACTIVE, state.getWorkflowStatus());
    }

    @Test
    void shouldAcceptJsonMapFromPersistentIdempotencyReplay() {
        ConversationState state = stateWaitingForConfirmation();
        state.startNewTurn("确认提交");
        TurnContext submitTurn = turn(state, "确认提交", "USER-002");
        assertInstanceOf(CapabilityAction.class, workflow.nextAction(submitTurn).getAction());

        // JDBC 幂等记录不会保存 Java 类名，重启后的 data 是普通 Map。
        CapabilityResult<Map<String, Object>> replayed = CapabilityResult.success(
                "return.apply",
                "local-return-domain",
                "submitted",
                Map.<String, Object>of(
                    "found", true,
                    "submitted", true,
                    "return_id", "RET-MAP-001",
                    "order_id", "ORD-003",
                    "status", "Under Review"
                )
        ).asReplayed();
        submitTurn.addObservation("capability_result", CapabilityObservation.from(replayed));

        FinalAnswerAction completed = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(submitTurn).getAction()
        );
        assertTrue(completed.getAnswer().contains("RET-MAP-001"));
        assertEquals(WorkflowStatus.COMPLETED, state.getWorkflowStatus());
    }

    @Test
    void shouldHandoffInsteadOfSavingHumanRequestAsReturnReason() {
        ConversationState state = startedState("我要退货", "USER-002");
        workflow.nextAction(turn(state, "我要退货", "USER-002"));
        state.startNewTurn("我要人工");

        WorkflowDecision decision = workflow.nextAction(turn(state, "我要人工", "USER-002"));

        assertInstanceOf(HandoffAction.class, decision.getAction());
        assertEquals(ReturnApplyStep.CANCELLED.name(), state.getWorkflowState(ReturnApplyWorkflow.ID, "step"));
        // HandoffAction 成功执行后，Runtime 会把全局终态写成 HANDED_OFF，而不是 USER_CANCELLED。
        assertEquals(WorkflowStatus.ACTIVE, state.getWorkflowStatus());
    }

    private ConversationState stateWaitingForConfirmation() {
        ConversationState state = startedState("我要退货 ORD-003，质量问题", "USER-002");
        TurnContext context = turn(state, "我要退货 ORD-003，质量问题", "USER-002");
        workflow.nextAction(context);
        observeSuccess(context,
                "order.lookup",
                "found",
                ownedOrder("USER-002")
        );
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

    private OrderSummary ownedOrder(String ownerId) {
        return new OrderSummary(
                "ORD-003",
                ownerId,
                "PROD-003",
                "USB-C 数据线",
                "已签收",
                "YT9876543210"
        );
    }

    /**
     * Workflow 单测模拟的是 Handler 写入的稳定观察结果，而不是旧 ToolRegistry 的返回对象。
     * 这样测试会在有人重新引入旧 {@code ToolResult} 依赖时立即暴露问题。
     */
    private void observeSuccess(TurnContext context, String capabilityId, String message, Object data) {
        CapabilityResult<Object> result = CapabilityResult.success(
                capabilityId,
                "workflow-test-provider",
                message,
                data
        );
        context.addObservation("capability_result", CapabilityObservation.from(result));
    }

    private void assertStep(ConversationState state, ReturnApplyStep expected) {
        assertEquals(
                expected.name(),
                state.getWorkflowState(ReturnApplyWorkflow.ID, "step")
        );
    }
}
