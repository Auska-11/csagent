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
import com.smartcs.smart_cs_agent.capability.api.contracts.ReturnStatusSummary;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.returnquery.ReturnQueryStep;
import com.smartcs.smart_cs_agent.workflow.returnquery.ReturnQueryWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReturnQueryWorkflow 的纯状态机测试。
 *
 * <p>这里不启动 Spring，也不访问 Repository。测试只给 Workflow 输入消息和 CapabilityObservation，
 * 因而一旦失败，可以快速判断问题出在“对话流程”，而不是数据库或 Provider。</p>
 */
class ReturnQueryWorkflowTest {

    private ReturnQueryWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new ReturnQueryWorkflow(new GlobalInterruptPolicy());
    }

    @Test
    void shouldKeepWaitingAcrossTurnsThenFormatStableContract() {
        ConversationState state = startedState("帮我查退货进度", "USER-002");

        WorkflowDecision first = workflow.nextAction(turn(state, "帮我查退货进度", "USER-002"));
        assertInstanceOf(AskUserAction.class, first.getAction());
        assertStep(state, ReturnQueryStep.WAITING_RETURN_ID);

        // 第二轮仍没有编号时，Workflow 必须保持等待，不能把普通文本误当成 return_id。
        state.startNewTurn("我还没找到编号");
        WorkflowDecision stillWaiting = workflow.nextAction(turn(state, "我还没找到编号", "USER-002"));
        assertInstanceOf(AskUserAction.class, stillWaiting.getAction());
        assertStep(state, ReturnQueryStep.WAITING_RETURN_ID);

        state.startNewTurn("RET-001");
        TurnContext lookupTurn = turn(state, "RET-001", "USER-002");
        CapabilityAction lookup = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(lookupTurn).getAction()
        );
        assertEquals(CapabilityIds.RETURN_LOOKUP, lookup.getCapability());
        assertEquals(Map.of("return_id", "RET-001"), lookup.getArguments());

        lookupTurn.addObservation("capability_result", success(new ReturnStatusSummary(
                "RET-001",
                "ORD-004",
                "Type-C 数据线",
                "数据线接口松动，充电不稳定",
                "审核通过"
        )));
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(lookupTurn).getAction()
        );

        assertTrue(answer.getAnswer().contains("RET-001"));
        assertTrue(answer.getAnswer().contains("Type-C 数据线"));
        assertTrue(answer.getAnswer().contains("审核通过"));
        assertStep(state, ReturnQueryStep.COMPLETED);
    }

    @Test
    void shouldAskForAnotherIdWhenReturnDoesNotExist() {
        ConversationState state = startedState("查询 RET-999 的退货状态", "USER-002");
        TurnContext context = turn(state, "查询 RET-999 的退货状态", "USER-002");
        workflow.nextAction(context);

        context.addObservation("capability_result", rejected("RETURN_NOT_FOUND"));
        AskUserAction answer = assertInstanceOf(
                AskUserAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getQuestion().contains("没有找到"));
        assertNull(state.getWorkflowState(ReturnQueryWorkflow.ID, "returnId"));
        assertStep(state, ReturnQueryStep.WAITING_RETURN_ID);
    }

    @Test
    void shouldNotLeakReturnDetailsWhenOwnershipCheckFails() {
        ConversationState state = startedState("查询 RET-001", "USER-001");
        TurnContext context = turn(state, "查询 RET-001", "USER-001");
        workflow.nextAction(context);

        context.addObservation("capability_result", rejected("RETURN_OWNERSHIP_MISMATCH"));
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("不属于当前登录用户"));
        assertFalse(answer.getAnswer().contains("ORD-004"));
        assertFalse(answer.getAnswer().contains("Type-C 数据线"));
        assertFalse(answer.getAnswer().contains("审核通过"));
    }

    @Test
    void shouldRequireAuthenticationBeforeCallingPrivateCapability() {
        ConversationState state = new ConversationState("查询 RET-001", "USER-002");
        state.startOrResumeWorkflow(ReturnQueryWorkflow.ID, ReturnQueryWorkflow.VERSION);
        TurnContext context = new TurnContext(
                new ChatRequest("conv-return-query", "USER-002", "查询 RET-001"),
                state
        );

        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("需要先登录"));
        assertStep(state, ReturnQueryStep.CANCELLED);
    }

    @Test
    void shouldHandoffInsteadOfTreatingHumanRequestAsReturnId() {
        ConversationState state = startedState("帮我查退货进度", "USER-002");
        workflow.nextAction(turn(state, "帮我查退货进度", "USER-002"));
        state.startNewTurn("我要人工客服");

        HandoffAction action = assertInstanceOf(
                HandoffAction.class,
                workflow.nextAction(turn(state, "我要人工客服", "USER-002")).getAction()
        );

        assertEquals("USER_REQUESTED_HUMAN", action.getReason());
        assertStep(state, ReturnQueryStep.CANCELLED);
    }

    private ConversationState startedState(String message, String userId) {
        ConversationState state = new ConversationState(message, userId);
        state.startOrResumeWorkflow(ReturnQueryWorkflow.ID, ReturnQueryWorkflow.VERSION);
        return state;
    }

    private TurnContext turn(ConversationState state, String message, String userId) {
        return new TurnContext(
                new ChatRequest(
                        "conv-return-query",
                        userId,
                        message,
                        AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER"))
                ),
                state
        );
    }

    private CapabilityObservation success(ReturnStatusSummary data) {
        return new CapabilityObservation(
                CapabilityIds.RETURN_LOOKUP,
                CapabilityOutcome.SUCCESS,
                "ok",
                data,
                null,
                "test-provider",
                false
        );
    }

    private CapabilityObservation rejected(String errorCode) {
        return new CapabilityObservation(
                CapabilityIds.RETURN_LOOKUP,
                CapabilityOutcome.BUSINESS_REJECTED,
                "rejected",
                null,
                errorCode,
                "test-provider",
                false
        );
    }

    private void assertStep(ConversationState state, ReturnQueryStep expected) {
        assertEquals(
                expected.name(),
                state.getWorkflowState(ReturnQueryWorkflow.ID, "step")
        );
    }
}
