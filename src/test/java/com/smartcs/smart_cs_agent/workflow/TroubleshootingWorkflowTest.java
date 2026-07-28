package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.action.LLMAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.troubleshooting.TroubleshootingWorkflow;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TroubleshootingWorkflowTest {

    private final TroubleshootingWorkflow workflow = new TroubleshootingWorkflow(new GlobalInterruptPolicy());

    @Test
    void shouldEmitLlmActionOnlyAfterReliableEvidence() {
        TurnContext context = turn("耳机听着听着只剩左边响了");

        CapabilityAction search = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(context).getAction()
        );
        assertEquals(CapabilityIds.KNOWLEDGE_SEARCH, search.getCapability());
        assertEquals("TROUBLESHOOTING", search.getArguments().get("knowledge_type"));
        assertEquals("headphones", search.getArguments().get("product_category"));

        context.addObservation("capability_result", success(List.of(evidence())));
        LLMAction llm = assertInstanceOf(LLMAction.class, workflow.nextAction(context).getAction());

        assertTrue(llm.getPrompt().contains("只能使用证据"));
        assertTrue(llm.getPrompt().contains("不得推断"));
        assertTrue(llm.getPrompt().contains("分步骤"));
        assertTrue(llm.getPrompt().contains("知识来源"));
        assertTrue(llm.getPrompt().contains("耳机手册/故障排查"));
        assertTrue(llm.getPrompt().contains("异常发热"));

        context.addObservation("llm_response", "1. 请重新配对左右耳。\n知识来源：耳机手册");
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );
        assertTrue(answer.getAnswer().contains("知识来源"));
    }

    @Test
    void shouldAskForDetailsWithoutCallingLlmWhenKnowledgeMisses() {
        TurnContext context = turn("耳机有问题，怎么处理");
        workflow.nextAction(context);
        context.addObservation("capability_result", success(List.of()));

        AskUserAction ask = assertInstanceOf(
                AskUserAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(ask.getQuestion().contains("完整型号"));
        assertTrue(ask.getQuestion().contains("停止操作"));
        assertEquals(true, context.getState().getWorkflowState(TroubleshootingWorkflow.ID, "waiting_details"));
    }

    @Test
    void shouldCancelWaitingTroubleshootingWithoutSearchingAgain() {
        ConversationState state = enterWaiting("耳机连不上");
        TurnContext cancelledTurn = nextTurn(state, "取消");

        CancelWorkflowAction cancel = assertInstanceOf(
                CancelWorkflowAction.class,
                workflow.nextAction(cancelledTurn).getAction()
        );

        assertTrue(cancel.getAnswer().contains("已取消"));
        assertTrue(state.getWorkflowStateSnapshot(TroubleshootingWorkflow.ID).isEmpty());
    }

    @Test
    void shouldHandoffWaitingTroubleshootingWithoutSearchingAgain() {
        ConversationState state = enterWaiting("耳机连不上");
        TurnContext handoffTurn = nextTurn(state, "我要转人工");

        HandoffAction handoff = assertInstanceOf(
                HandoffAction.class,
                workflow.nextAction(handoffTurn).getAction()
        );

        assertEquals(GlobalInterruptPolicy.REASON_USER_REQUESTED_HUMAN, handoff.getReason());
        assertTrue(handoff.getSummary().contains("商品故障排查"));
        assertTrue(handoff.getContext().containsKey("original_query"));
        assertTrue(state.getWorkflowStateSnapshot(TroubleshootingWorkflow.ID).isEmpty());
    }

    @Test
    void shouldContinueSearchForOrdinarySupplementAndKeepFullQueryInPrompt() {
        ConversationState state = enterWaiting("耳机连不上");
        TurnContext supplementTurn = nextTurn(state, "Pro Max，白灯一直闪");

        CapabilityAction search = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(supplementTurn).getAction()
        );
        String fullQuery = String.valueOf(search.getArguments().get("query"));
        assertTrue(fullQuery.contains("耳机连不上"));
        assertTrue(fullQuery.contains("Pro Max，白灯一直闪"));

        supplementTurn.addObservation("capability_result", success(List.of(evidence())));
        LLMAction llm = assertInstanceOf(LLMAction.class, workflow.nextAction(supplementTurn).getAction());

        assertTrue(llm.getPrompt().contains("耳机连不上"));
        assertTrue(llm.getPrompt().contains("Pro Max，白灯一直闪"));
        assertEquals(1, occurrences(llm.getPrompt(), "耳机连不上"));
        assertEquals(1, occurrences(llm.getPrompt(), "Pro Max，白灯一直闪"));
    }

    @Test
    void shouldNotTakeWarrantyPolicyOrRealtimeAfterSalesRequests() {
        assertEquals(0, match("蓝牙耳机保修多久"));
        assertEquals(0, match("我要退货，查询退货进度"));
        assertTrue(match("数据线只能充电不能传文件") > 0.99);
    }

    private double match(String message) {
        ConversationState state = new ConversationState(message, "USER-001");
        return workflow.match(new RoutingContext(
                new ChatRequest("conv-trouble", "USER-001", message),
                state
        )).getScore();
    }

    private TurnContext turn(String message) {
        ConversationState state = new ConversationState(message, "USER-001");
        state.startOrResumeWorkflow(TroubleshootingWorkflow.ID, TroubleshootingWorkflow.VERSION);
        return new TurnContext(new ChatRequest("conv-trouble", "USER-001", message), state);
    }

    private ConversationState enterWaiting(String message) {
        TurnContext context = turn(message);
        workflow.nextAction(context);
        context.addObservation("capability_result", success(List.of()));
        assertInstanceOf(AskUserAction.class, workflow.nextAction(context).getAction());
        return context.getState();
    }

    private TurnContext nextTurn(ConversationState state, String message) {
        state.startNewTurn(message);
        return new TurnContext(new ChatRequest("conv-trouble", "USER-001", message), state);
    }

    private int occurrences(String value, String expected) {
        return value.split(java.util.regex.Pattern.quote(expected), -1).length - 1;
    }

    private CapabilityObservation success(List<KnowledgeSearchHit> hits) {
        return new CapabilityObservation(
                CapabilityIds.KNOWLEDGE_SEARCH,
                CapabilityOutcome.SUCCESS,
                "ok",
                hits,
                null,
                "test-provider",
                false
        );
    }

    private KnowledgeSearchHit evidence() {
        return new KnowledgeSearchHit(
                "troubleshooting/headphones_pro_max.md",
                "troubleshooting/headphones_pro_max.md#chunk-1",
                "TROUBLESHOOTING",
                "troubleshooting",
                "headphones",
                "Pro Max",
                "蓝牙耳机 Pro Max / 单侧声道无输出",
                "耳机手册/故障排查",
                "1.2",
                92,
                "单侧声道无输出时，请重新配对左右耳并重置蓝牙连接。"
        );
    }
}
