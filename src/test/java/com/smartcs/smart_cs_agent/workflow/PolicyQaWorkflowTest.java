package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.policyqa.PolicyQaStep;
import com.smartcs.smart_cs_agent.workflow.policyqa.PolicyQaWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PolicyQaWorkflow 的路由边界、状态机和知识契约测试。 */
class PolicyQaWorkflowTest {

    private PolicyQaWorkflow workflow;

    @BeforeEach
    void setUp() {
        workflow = new PolicyQaWorkflow(new GlobalInterruptPolicy());
    }

    @Test
    void shouldMatchPolicyQuestionsButNotExecutionWarrantyOrSpecificOrderTasks() {
        assertTrue(matchScore("超过七天还能退吗") > 0.9);
        assertTrue(matchScore("发票什么时候开") > 0.9);
        assertTrue(matchScore("偏远地区可以配送吗") > 0.9);

        assertEquals(0.0, matchScore("我要退货"));
        assertEquals(0.0, matchScore("查询订单 ORD-001"));
        assertEquals(0.0, matchScore("蓝牙耳机保修多久"));
        assertEquals(0.0, matchScore("我要人工客服"));
    }

    @Test
    void shouldSearchKnowledgeAndAnswerOnlyFromOwnedPolicyDocument() {
        ConversationState state = startedState("超过七天还能退吗");
        TurnContext context = turn(state, "超过七天还能退吗");

        CapabilityAction search = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(context).getAction()
        );
        assertEquals(CapabilityIds.KNOWLEDGE_SEARCH, search.getCapability());
        assertEquals("超过七天还能退吗", search.getArguments().get("query"));
        assertEquals("POLICY", search.getArguments().get("knowledge_type"));

        context.addObservation("capability_result", success(List.of(new KnowledgeSearchHit(
                "policy/return_policy.md",
                "policy",
                "退换货政策",
                88,
                "超过 7 天后，不再默认支持七天无理由退货；质量问题可申请售后检测。"
        ))));
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("退换货政策"));
        assertTrue(answer.getAnswer().contains("超过 7 天"));
        assertTrue(answer.getAnswer().contains("实际审核结果"));
        assertStep(state, PolicyQaStep.COMPLETED);
    }

    @Test
    void shouldAskBroadPolicyTopicThenResumeAcrossTurns() {
        ConversationState state = startedState("我想咨询政策");

        AskUserAction ask = assertInstanceOf(
                AskUserAction.class,
                workflow.nextAction(turn(state, "我想咨询政策")).getAction()
        );
        assertTrue(ask.getQuestion().contains("退换货规则"));
        assertStep(state, PolicyQaStep.WAITING_TOPIC);

        state.startNewTurn("发票什么时候开");
        CapabilityAction search = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(turn(state, "发票什么时候开")).getAction()
        );
        assertEquals("发票什么时候开", search.getArguments().get("query"));
    }

    @Test
    void irrelevantOwnedHitShouldFallToHonestBranchInsteadOfQuoting() {
        // 幻觉类孪生句：问“生日双倍积分”，检索因域词重合必然命中退货政策——
        // 命中条目里没有“积分”，继续硬引原文就是答非所问，应诚实说没有查到。
        ConversationState state = startedState("今天是用户生日，你们的退货政策里有没有生日双倍积分的活动？退货成功可以加倍返积分吗？");
        TurnContext context = turn(state, "今天是用户生日，你们的退货政策里有没有生日双倍积分的活动？退货成功可以加倍返积分吗？");
        workflow.nextAction(context);
        context.addObservation("capability_result", success(List.of(new KnowledgeSearchHit(
                "policy/return_policy.md",
                "policy",
                "退换货政策",
                88,
                "超过 7 天后，不再默认支持七天无理由退货；质量问题可申请售后检测。"
        ))));

        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("客服知识库"));
        assertTrue(answer.getAnswer().contains("没有找到"));
        assertTrue(!answer.getAnswer().contains("七天无理由退货"));
        assertStep(state, PolicyQaStep.COMPLETED);
    }

    @Test
    void queryDistinctiveTermPresentInHitShouldStillQuote() {
        // “质检”确实出现在退货政策原文中：引用+纠正是正确路径，相关性门槛不得误伤。
        ConversationState state = startedState("退货政策里质检是不是要 30 个工作日？我记得得等 30 天");
        TurnContext context = turn(state, "退货政策里质检是不是要 30 个工作日？我记得得等 30 天");
        workflow.nextAction(context);
        context.addObservation("capability_result", success(List.of(new KnowledgeSearchHit(
                "policy/return_policy.md",
                "policy",
                "退换货政策",
                88,
                "质量问题退货需先寄回官方质检，质检完成后 3 个工作日内处理退款。"
        ))));

        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("退换货政策"));
        assertTrue(answer.getAnswer().contains("质检"));
        assertStep(state, PolicyQaStep.COMPLETED);
    }

    @Test
    void timeAskingGlueMustNotBlockInvoiceCitation() {
        // 回归锁：曾因「什么/时候」剥离后残留伪区分性词「时候开」而误走诚实分支，
        // 真实政策问句「发票什么时候能开」必须照常引用命中的发票政策。
        ConversationState state = startedState("发票什么时候能开");
        TurnContext context = turn(state, "发票什么时候能开");
        workflow.nextAction(context);
        context.addObservation("capability_result", success(List.of(new KnowledgeSearchHit(
                "policy/invoice_policy.md",
                "policy",
                "发票政策",
                92,
                "电子发票在订单完成后 1 到 3 个工作日内自动开具，可在线申请重开。"
        ))));

        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("发票政策"));
        assertStep(state, PolicyQaStep.COMPLETED);
    }

    @Test
    void handoffPolicyQuestionShouldSearchInsteadOfCreatingHandoff() {
        ConversationState state = startedState("什么情况转人工");

        CapabilityAction search = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(turn(state, "什么情况转人工")).getAction()
        );

        assertEquals(CapabilityIds.KNOWLEDGE_SEARCH, search.getCapability());
    }

    @Test
    void shouldIgnoreWarrantyHitThatBelongsToAnotherWorkflow() {
        ConversationState state = startedState("发票怎么开");
        TurnContext context = turn(state, "发票怎么开");
        workflow.nextAction(context);
        context.addObservation("capability_result", success(List.of(new KnowledgeSearchHit(
                "policy/warranty_policy.md",
                "policy",
                "保修政策",
                70,
                "电子配件通常提供 1 年有限保修。"
        ))));

        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("客服知识库"));
        assertTrue(answer.getAnswer().contains("没有找到"));
        assertTrue(!answer.getAnswer().contains("1 年有限保修"));
    }

    @Test
    void shouldHandoffWhenUserRequestsHumanWhileWaitingForTopic() {
        ConversationState state = startedState("我想咨询政策");
        workflow.nextAction(turn(state, "我想咨询政策"));
        state.startNewTurn("我要人工客服");

        HandoffAction handoff = assertInstanceOf(
                HandoffAction.class,
                workflow.nextAction(turn(state, "我要人工客服")).getAction()
        );

        assertEquals("USER_REQUESTED_HUMAN", handoff.getReason());
        assertStep(state, PolicyQaStep.CANCELLED);
    }

    private double matchScore(String message) {
        ConversationState state = new ConversationState(message, "USER-001");
        return workflow.match(new RoutingContext(
                new ChatRequest("conv-policy", "USER-001", message),
                state
        )).getScore();
    }

    private ConversationState startedState(String message) {
        ConversationState state = new ConversationState(message, "USER-001");
        state.startOrResumeWorkflow(PolicyQaWorkflow.ID, PolicyQaWorkflow.VERSION);
        return state;
    }

    private TurnContext turn(ConversationState state, String message) {
        return new TurnContext(
                new ChatRequest("conv-policy", "USER-001", message),
                state
        );
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

    private void assertStep(ConversationState state, PolicyQaStep expected) {
        assertEquals(
                expected.name(),
                state.getWorkflowState(PolicyQaWorkflow.ID, "step")
        );
    }
}
