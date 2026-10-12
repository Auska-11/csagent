package com.smartcs.smart_cs_agent.workflow.bargain;

import com.smartcs.smart_cs_agent.testing.XianyuTestSupport;
import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.ItemFacts;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.PriceRange;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuScenarioProperties;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSellerRuleService;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemProfileStore;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuWorkflowEligibilityPolicy;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuScenarioProperties.SellerRules;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSidecarProperties;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRegistry;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouter;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouterProperties;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouteResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BargainWorkflow 的路由门控与议价决策矩阵测试。
 *
 * <p>核心不变量：报价、承诺与发给买家的全部话术由代码按卖家授权规则确定性生成，
 * 不经模型措辞——因此断言对象是买家实际收到的 AskUserAction 话术与议价状态，
 * 底线价（floorPrice）绝不出现在任何买家可见字段里。</p>
 */
class BargainWorkflowTest {

    private static final String CONV = "xianyu_buyer-01_1079979582985";

    /** 挂牌价 265 的测试商品；价格解析依赖真实 ItemFacts 结构。 */
    private static final ItemFacts FACTS = new ItemFacts(
            "1079979582985",
            "森海塞尔MOMENTUM二代头戴耳机",
            new PriceRange(new BigDecimal("265"), new BigDecimal("265")),
            1,
            "森海塞尔 MOMENTUM 2 代头戴式蓝牙耳机。",
            "功能未测试；使用痕迹"
    );

    private final StubItemFactService factService = new StubItemFactService(FACTS);

    @Test
    void bargainExpressionInXianyuSessionScoresAboveAdvisory() {
        BargainWorkflow workflow = workflow(XianyuScenarioProperties.defaults());
        assertEquals(0.985, workflow.match(routing("xianyu", CONV, "250卖吗")).getScore());
        assertEquals(0.985, workflow.match(routing("xianyu", CONV, "能便宜点吗")).getScore());
        assertEquals(0.985, workflow.match(routing("xianyu", CONV, "包邮吗")).getScore());
        // 纯数字出价（买家直接发“250”）是合法议价信号。
        assertEquals(0.985, workflow.match(routing("xianyu", CONV, "250")).getScore());
    }

    @Test
    void nonXianyuSessionsNeverMatchBargain() {
        BargainWorkflow workflow = workflow(XianyuScenarioProperties.defaults());
        assertEquals(0.0, workflow.match(routing("default", "conv-demo-001", "250卖吗")).getScore());
        // 闲鱼租户但会话身份不符合 xianyu_{buyerId}_{itemId} 的同样不进入。
        assertEquals(0.0, workflow.match(routing("xianyu", "conv-001", "250卖吗")).getScore());
    }

    @Test
    void bareUnitPriceMentionMustNotTriggerBargainRouting() {
        BargainWorkflow workflow = workflow(XianyuScenarioProperties.defaults());
        // “这耳机100块的东西还在吗”只是询问商品，裸“N块/N元”不在路由门控里。
        assertEquals(0.0, workflow.match(routing("xianyu", CONV, "这耳机100块的东西还在吗")).getScore());
        // 政策/退货/订单物流/人工请求各有归属，不属于议价协商。
        assertEquals(0.0, workflow.match(routing("xianyu", CONV, "退货运费谁承担")).getScore());
        assertEquals(0.0, workflow.match(routing("xianyu", CONV, "这个快递单号能查下吗")).getScore());
        assertEquals(0.0, workflow.match(routing("xianyu", CONV, "我要人工客服")).getScore());
    }

    @Test
    void unauthorizedBargainAlwaysDefersToSellerWithoutRejecting() {
        ConversationState state = startedState("250卖吗");
        HandoffAction handoff = handoff(workflow(XianyuScenarioProperties.defaults()), state, "250卖吗");

        // 未授权议价不再“嘴上转人工、实际结束”：必须真正暂停机器人交给卖家本人。
        assertTrue(handoff.isPauseOnly(), "未授权议价必须 pauseOnly 暂停，而不是仅生成提示词");
        assertEquals("SELLER_CONFIRMATION_REQUIRED", handoff.getReason());
        assertTrue(handoff.getBuyerReply().contains("做不了主"), "买家侧话术必须说明做不了主并转告卖家");
        // 买家出价已入状态，便于卖家核对。
        assertEquals("250", state.getWorkflowState(BargainWorkflow.ID, "buyerOffer"));
        assertEquals("COMPLETED", state.getWorkflowState(BargainWorkflow.ID, "step"));
    }

    @Test
    void floorPriceNeverLeaksIntoSellerConfirmPause() {
        // 配置了底线价但未授权议价：暂停摘要与买家话术都不得出现底线价。
        SellerRules rules = new SellerRules();
        rules.setBargainAccepted(false);
        rules.setFloorPrice(new BigDecimal("240"));
        XianyuScenarioProperties scenario = XianyuScenarioProperties.of(
                new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds()), rules);
        ConversationState state = startedState("230卖吗");
        HandoffAction handoff = handoff(workflow(scenario), state, "230卖吗");

        assertTrue(handoff.isPauseOnly());
        assertFalse(handoff.getSummary().contains("240"), "底线价不得进入暂停摘要");
        assertFalse(handoff.getBuyerReply().contains("240"), "底线价不得进入买家话术");
        assertTrue(handoff.getSummary().contains("¥230"), "摘要应含买家出价便于卖家拍板");
    }

    @Test
    void offerAtOrAboveListPriceAcceptsWithoutTouchingBargainAuthority() {
        // 270 ≥ 挂牌 265：按在售价成交，与议价授权无关。
        AskUserAction action = respond(workflow(authorizedScenario()), startedState("270卖吗"), "270卖吗");
        assertTrue(action.getQuestion().contains("¥270"), "买家收到的是按出价成交: " + action.getQuestion());
        assertTrue(action.getQuestion().contains("成交"));
        assertFalse(action.getQuestion().contains("¥265"), "出价高于挂牌时不得改口按挂牌价成交");
    }

    @Test
    void offerAboveFloorAcceptsWithinSellerAuthority() {
        // 授权：底线 240、单次让价 20。买家出 250 ≥ 240，直接答应。
        ConversationState state = startedState("250卖吗");
        AskUserAction action = respond(workflow(authorizedScenario()), state, "250卖吗");

        assertTrue(action.getQuestion().contains("¥250"), "买家收到按出价成交的承诺: " + action.getQuestion());
        assertTrue(action.getQuestion().contains("拍下"), "引导拍下不付款、等卖家改价");
        assertEquals("250", state.getWorkflowState(BargainWorkflow.ID, "lastQuote"));
        // 底线价 240 不得出现在买家可见话术的任何位置。
        assertFalse(action.getQuestion().contains("240"), "底线价不得进入买家话术");
    }

    @Test
    void lowballOfferGetsAuthorizedCounterNeverBelowFloor() {
        // 200 < 底线 240 → 还价 挂牌265-让价20=245（不低于底线）。
        AskUserAction action = respond(workflow(authorizedScenario()), startedState("200卖吗"), "200卖吗");
        assertTrue(action.getQuestion().contains("¥245"), "买家收到授权还价: " + action.getQuestion());
        assertTrue(action.getQuestion().contains("拍下"));
        assertFalse(action.getQuestion().contains("240"), "底线价不得进入买家话术");
        assertFalse(action.getQuestion().contains("¥200"), "低于底线的出价绝不被接受");
    }

    @Test
    void discountRequestIsConvertedToAbsoluteOfferThenDecided() {
        // “便宜50”折算为 265-50=215，低于底线 → 还价 245；折算价本身不直接报出。
        AskUserAction action = respond(workflow(authorizedScenario()), startedState("便宜50"), "便宜50");
        assertTrue(action.getQuestion().contains("¥245"));
        assertFalse(action.getQuestion().contains("215"), "折算中间价不得直接报给买家");
    }

    @Test
    void noOfferMessageStillQuotesAuthorizedCounter() {
        // 未给价的砍价（“再便宜点吧”）按授权还价，不虚构额外优惠。
        AskUserAction action = respond(workflow(authorizedScenario()), startedState("再便宜点吧"), "再便宜点吧");
        assertTrue(action.getQuestion().contains("¥245"));
    }

    @Test
    void missingItemFactsMustNotInventPriceOrAutoAccept() {
        // 商品知识未同步：不得凭全局底线价答应出价，必须暂停转卖家确认。
        StubItemFactService emptyFacts = new StubItemFactService(null);
        BargainWorkflow workflow = new BargainWorkflow(
                new GlobalInterruptPolicy(), emptyFacts, XianyuTestSupport.sellerRules(authorizedScenario()));
        ConversationState state = startedState("250卖吗");
        HandoffAction handoff = handoff(workflow, state, "250卖吗");

        assertTrue(handoff.isPauseOnly(), "事实缺失必须暂停机器人，而不是按底线授权成交");
        assertEquals("SELLER_CONFIRMATION_REQUIRED", handoff.getReason());
        assertTrue(handoff.getBuyerReply().contains("还没同步"), "买家侧必须说明信息未同步");
        assertEquals("250", state.getWorkflowState(BargainWorkflow.ID, "buyerOffer"),
                "出价仍要记录，供卖家核对");
        assertTrue(handoff.getSummary().contains("¥250"));
        assertFalse(handoff.getSummary().contains("240"), "底线价不得进入摘要");
    }

    @Test
    void soldOutItemNeverQuotesOrDeals() {
        // 库存已知为 0：不报价、不成交，直接终态告知。
        ItemFacts soldOut = new ItemFacts(
                "1079979582985", "森海塞尔MOMENTUM二代头戴耳机",
                new PriceRange(new BigDecimal("265"), new BigDecimal("265")),
                0, "", "");
        BargainWorkflow workflow = new BargainWorkflow(
                new GlobalInterruptPolicy(), new StubItemFactService(soldOut), XianyuTestSupport.sellerRules(authorizedScenario()));
        ConversationState state = startedState("250卖吗");

        WorkflowDecision decision = workflow.nextAction(turn(state, "250卖吗"));
        FinalAnswerAction answer = assertInstanceOf(FinalAnswerAction.class, decision.getAction());
        assertTrue(answer.getAnswer().contains("售出"), "无货必须直接告知售出");
        assertEquals("COMPLETED", state.getWorkflowState(BargainWorkflow.ID, "step"));
        assertNull(state.getWorkflowState(BargainWorkflow.ID, "lastQuote"), "无货不得产生报价");
    }

    @Test
    void shippingRequestWithoutAuthorizationPausesForSeller() {
        ConversationState state = startedState("包邮吗");
        HandoffAction handoff = handoff(workflow(XianyuScenarioProperties.defaults()), state, "包邮吗");

        assertTrue(handoff.isPauseOnly(), "未授权的包邮必须暂停转卖家确认");
        assertEquals("SELLER_CONFIRMATION_REQUIRED", handoff.getReason());
        assertTrue(handoff.getBuyerReply().contains("做不了主"));
        assertTrue(handoff.getSummary().contains("发货/包邮超出机器人授权"));
    }

    @Test
    void authorizedPriceWithUnauthorizedShippingStillStatesQuoteBeforePausing() {
        // 混合场景：价格已授权（还价 245）但包邮未授权——报价照说，包邮转卖家确认。
        ConversationState state = startedState("245卖吗，包邮吗");
        HandoffAction handoff = handoff(workflow(authorizedScenario()), state, "245卖吗，包邮吗");

        assertTrue(handoff.isPauseOnly());
        assertTrue(handoff.getBuyerReply().contains("¥245"), "已授权报价必须在暂停前说清");
        assertTrue(handoff.getBuyerReply().contains("做不了主"));
    }

    @Test
    void quoteTurnEmitsAskUserDirectlyWithExactAuthorizedTemplate() {
        BargainWorkflow workflow = workflow(authorizedScenario());
        ConversationState state = startedState("250卖吗");

        // 报价轮直接以 AskUserAction 收尾：不存在 LLMAction，模型措辞没有入口，
        // 买家收到的就是这段代码生成的承诺原文。
        AskUserAction ask = respond(workflow, state, "250卖吗");
        assertEquals("可以的，¥250 成交。您先拍下不要付款，我同步卖家改好价格您再支付。",
                ask.getQuestion());
        assertEquals("WAITING_BUYER", state.getWorkflowState(BargainWorkflow.ID, "step"));
        assertEquals("250", state.getWorkflowState(BargainWorkflow.ID, "lastQuote"));
    }

    @Test
    void agreedQuoteSurvivesNextTurnWithoutInflation() {
        // 审阅复现：turn1 “240卖吗”→ACCEPT 240；turn2 “再便宜点”不得改口重报 245。
        BargainWorkflow workflow = workflow(authorizedScenario());
        ConversationState state = startedState("240卖吗");

        AskUserAction first = respond(workflow, state, "240卖吗");
        assertTrue(first.getQuestion().contains("¥240"));

        TurnContext secondTurn = turn(state, "再便宜点");
        AskUserAction second = assertInstanceOf(AskUserAction.class,
                workflow.nextAction(secondTurn).getAction());
        assertTrue(second.getQuestion().contains("¥240"), "已同意的价格跨轮必须保持一致");
        assertTrue(second.getQuestion().contains("没有再让的空间"),
                "连续砍价必须重申一口价而不是重新让价: " + second.getQuestion());
        assertFalse(second.getQuestion().contains("245"), "不得重新报出公式价 245");
    }

    @Test
    void renewedLowerOfferAfterAcceptanceNeverRaisesQuote() {
        // 评审复现：第一轮已按 240 同意，第二轮“200卖吗”不得重新报 245——
        // 带金额还价与无金额追问同样受上一口价约束。
        BargainWorkflow workflow = workflow(authorizedScenario());
        ConversationState state = startedState("240卖吗");
        respond(workflow, state, "240卖吗");

        AskUserAction second = respond(workflow, state, "200卖吗");
        assertTrue(second.getQuestion().contains("¥240"), "还价不得高于上一口价: " + second.getQuestion());
        assertTrue(second.getQuestion().contains("没有再让的空间"));
        assertFalse(second.getQuestion().contains("245"), "不得重新报出公式价 245");
        assertFalse(second.getQuestion().contains("¥200"), "低于已同意价的出价绝不被接受");
        assertEquals("240", state.getWorkflowState(BargainWorkflow.ID, "lastQuote"));
    }

    @Test
    void mirroredQuoteSurvivesNamespaceClearingLikeHandoff() {
        // 暂停转卖家会清空 bargain 命名空间：taskState 镜像必须保住已报价口径。
        BargainWorkflow workflow = workflow(authorizedScenario());
        ConversationState state = startedState("240卖吗");
        respond(workflow, state, "240卖吗");
        assertEquals("240", state.getTaskState("bargain_last_quote"));

        state.markWorkflowHandedOff();
        assertNull(state.getWorkflowState(BargainWorkflow.ID, "lastQuote"), "命名空间应被清理");

        TurnContext resumed = turn(state, "再便宜点");
        state.startOrResumeWorkflow(BargainWorkflow.ID, BargainWorkflow.VERSION);
        AskUserAction action = assertInstanceOf(AskUserAction.class,
                workflow.nextAction(resumed).getAction());
        assertTrue(action.getQuestion().contains("¥240"), "镜像要保证恢复后不改口: " + action.getQuestion());
    }

    @Test
    void staleQuoteAboveNewListPriceIsDiscarded() {
        // 改价热更新（挂牌 265→200）后，旧报价 240 已高于现挂牌：视为过期，按现价重新计算。
        StubItemFactService facts = new StubItemFactService(FACTS);
        SellerRules rules = new SellerRules();
        rules.setBargainAccepted(true);
        rules.setFloorPrice(new BigDecimal("190"));
        rules.setAllowedDiscount(new BigDecimal("5"));
        XianyuScenarioProperties scenario = XianyuScenarioProperties.of(
                new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds()), rules);
        BargainWorkflow workflow = new BargainWorkflow(new GlobalInterruptPolicy(), facts, XianyuTestSupport.sellerRules(scenario));
        ConversationState state = startedState("240卖吗");
        AskUserAction first = respond(workflow, state, "240卖吗");
        assertTrue(first.getQuestion().contains("¥240"));
        assertEquals("240", state.getTaskState("bargain_last_quote"));

        // 卖家改价 265→200：上一轮报价 240 高于现挂牌，不得再向买家重申。
        facts.setFacts(new ItemFacts(
                "1079979582985", "森海塞尔MOMENTUM二代头戴耳机",
                new PriceRange(new BigDecimal("200"), new BigDecimal("200")),
                1, "", ""));

        AskUserAction second = assertInstanceOf(AskUserAction.class,
                workflow.nextAction(turn(state, "再便宜点")).getAction());
        assertTrue(second.getQuestion().contains("¥195"), "旧报价 240 已过期，按新价口径还价 195: "
                + second.getQuestion());
        assertFalse(second.getQuestion().contains("240"), "过期报价不得出现在买家话术里");
    }

    @Test
    void acknowledgmentAfterSellOutRefusesToCloseAtStaleQuote() {
        // 评审复现：报价 250 后库存热更新为 0，买家回“好的”仍按 ¥250 收尾——
        // 确认分支必须与首次报价过同一套库存校验。
        StubItemFactService facts = new StubItemFactService(FACTS);
        BargainWorkflow workflow = new BargainWorkflow(
                new GlobalInterruptPolicy(), facts, XianyuTestSupport.sellerRules(authorizedScenario()));
        ConversationState state = startedState("250卖吗");
        AskUserAction quote = respond(workflow, state, "250卖吗");
        assertTrue(quote.getQuestion().contains("¥250"));

        facts.setFacts(new ItemFacts(
                "1079979582985", "森海塞尔MOMENTUM二代头戴耳机",
                new PriceRange(new BigDecimal("265"), new BigDecimal("265")),
                0, "", ""));

        FinalAnswerAction answer = assertInstanceOf(FinalAnswerAction.class,
                workflow.nextAction(turn(state, "好的")).getAction());
        assertTrue(answer.getAnswer().contains("售出"), "无货必须直接告知售出: " + answer.getAnswer());
        assertFalse(answer.getAnswer().contains("¥250"), "库存已为 0，不得再按旧报价承诺成交");
        assertFalse(answer.getAnswer().contains("拍下"), "无货不得再引导拍下");
        assertEquals("COMPLETED", state.getWorkflowState(BargainWorkflow.ID, "step"));
    }

    @Test
    void acknowledgmentAfterAuthorityRevokedPausesForSellerInsteadOfClosing() {
        // 评审复现：撤销议价授权后买家确认，仍按旧价收尾——授权撤销后历史报价
        // 不再代表卖家承诺，必须暂停转卖家本人，绝不按 ¥250 收尾。
        SellerRules rules = new SellerRules();
        rules.setBargainAccepted(true);
        rules.setFloorPrice(new BigDecimal("240"));
        rules.setAllowedDiscount(new BigDecimal("20"));
        XianyuScenarioProperties scenario = XianyuScenarioProperties.of(
                new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds()), rules);
        BargainWorkflow workflow = new BargainWorkflow(new GlobalInterruptPolicy(), factService, XianyuTestSupport.sellerRules(scenario));
        ConversationState state = startedState("250卖吗");
        respond(workflow, state, "250卖吗");

        rules.setBargainAccepted(false); // 卖家在会话中途撤销议价授权
        HandoffAction handoff = assertInstanceOf(HandoffAction.class,
                workflow.nextAction(turn(state, "好的")).getAction());
        assertTrue(handoff.isPauseOnly(), "授权撤销后确认必须转卖家本人，而不是按旧价成交");
        assertTrue(handoff.getBuyerReply().contains("卖家本人"), handoff.getBuyerReply());
        assertFalse(handoff.getBuyerReply().contains("¥250"), "不得再按撤销授权前的旧价承诺");
    }

    @Test
    void buyerAcknowledgmentClosesBargainAtQuotedPrice() {
        BargainWorkflow workflow = workflow(authorizedScenario());
        ConversationState state = startedState("240卖吗");
        respond(workflow, state, "240卖吗");
        assertEquals("WAITING_BUYER", state.getWorkflowState(BargainWorkflow.ID, "step"));

        // “好的”/“240成交”都是对已报价格的确认：收尾并重申价格。
        FinalAnswerAction ack = assertInstanceOf(FinalAnswerAction.class,
                workflow.nextAction(turn(state, "好的，240成交")).getAction());
        assertTrue(ack.getAnswer().contains("¥240"), "收尾话术必须重申已报价格");
        assertTrue(ack.getAnswer().contains("拍下"), "引导拍下不付款、等改价");
        assertEquals("COMPLETED", state.getWorkflowState(BargainWorkflow.ID, "step"));
    }

    @Test
    void waitingWorkflowOnlyAcceptsBargainOrAcknowledgment() {
        BargainWorkflow workflow = workflow(authorizedScenario());
        ConversationState state = startedState("240卖吗");
        respond(workflow, state, "240卖吗");
        RoutingContext routing = routing("xianyu", CONV, "好的");

        assertTrue(workflow.acceptsMessage(routing), "简短确认属于等待中的议价任务");
        assertTrue(workflow.acceptsMessage(routing("xianyu", CONV, "再便宜点")));
        assertFalse(workflow.acceptsMessage(routing("xianyu", CONV, "这耳机的成色怎么样")),
                "商品咨询应拒接，让 Router 重新路由");
        assertFalse(workflow.acceptsMessage(routing("xianyu", CONV, "我要退货")));
        assertFalse(workflow.acceptsMessage(routing("xianyu", CONV, "订单什么时候发货")));
    }

    @Test
    void continuedBargainQuestionIsNotMisreadAsAcknowledgment() {
        // “可以便宜点吗”包含“可以”但语义是继续砍价，绝不能当成交确认收尾。
        BargainWorkflow workflow = workflow(authorizedScenario());
        ConversationState state = startedState("240卖吗");
        respond(workflow, state, "240卖吗");

        WorkflowDecision decision = workflow.nextAction(turn(state, "可以便宜点吗"));
        assertInstanceOf(AskUserAction.class, decision.getAction(), "继续砍价必须继续议价而不是误判成交");
    }

    @Test
    void cancelInterruptTerminatesBargain() {
        BargainWorkflow workflow = workflow(authorizedScenario());
        ConversationState state = startedState("算了，议价不要了");

        assertInstanceOf(
                CancelWorkflowAction.class,
                workflow.nextAction(turn(state, "算了，议价不要了")).getAction()
        );
        assertEquals("CANCELLED", state.getWorkflowState(BargainWorkflow.ID, "step"));
    }

    @Test
    void handoffInterruptCarriesBargainContext() {
        BargainWorkflow workflow = workflow(authorizedScenario());
        ConversationState state = startedState("我要人工客服");

        HandoffAction handoff = assertInstanceOf(
                HandoffAction.class,
                workflow.nextAction(turn(state, "我要人工客服")).getAction()
        );
        assertEquals("USER_REQUESTED_HUMAN", handoff.getReason());
        assertEquals("CANCELLED", state.getWorkflowState(BargainWorkflow.ID, "step"));
    }

    @Test
    void routerSelectsBargainOnlyInXianyuSessions() {
        BargainWorkflow bargain = workflow(authorizedScenario());
        FallbackWorkflow fallback = new FallbackWorkflow();
        WorkflowRouterProperties properties = new WorkflowRouterProperties();
        properties.setFallbackWorkflowId("fallback");
        WorkflowRouter router = new WorkflowRouter(
                new WorkflowRegistry(List.of(bargain, fallback), List.of()),
                properties,
                new GlobalInterruptPolicy(),
                List.of(new XianyuWorkflowEligibilityPolicy(
                        factService, XianyuScenarioProperties.defaults()))
        );

        WorkflowRouteResult xianyu = router.route(
                request("xianyu", CONV, "250卖吗"),
                new ConversationState("250卖吗", "USER-XY-TEST")
        );
        assertEquals(BargainWorkflow.ID, xianyu.getSelectedWorkflow().descriptor().getId());
        assertEquals(0.985, xianyu.getSelectedScore(), 1e-9);

        WorkflowRouteResult demo = router.route(
                new ChatRequest("conv-demo-001", "USER-001", "250卖吗"),
                new ConversationState("250卖吗", "USER-001")
        );
        assertEquals("fallback", demo.getSelectedWorkflow().descriptor().getId(),
                "演示租户的议价消息必须保持原路由");
    }

    private BargainWorkflow workflow(XianyuScenarioProperties scenario) {
        return new BargainWorkflow(new GlobalInterruptPolicy(), factService, XianyuTestSupport.sellerRules(scenario));
    }

    /** 授权场景：接受议价、底线 240、单次让价 20（还价 = max(265-20, 240) = 245）。 */
    private XianyuScenarioProperties authorizedScenario() {
        SellerRules rules = new SellerRules();
        rules.setBargainAccepted(true);
        rules.setFloorPrice(new BigDecimal("240"));
        rules.setAllowedDiscount(new BigDecimal("20"));
        return XianyuScenarioProperties.of(new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds()), rules);
    }

    private ConversationState startedState(String message) {
        ConversationState state = new ConversationState(message, "USER-XY-TEST");
        state.startOrResumeWorkflow(BargainWorkflow.ID, BargainWorkflow.VERSION);
        return state;
    }

    private TurnContext turn(ConversationState state, String message) {
        return new TurnContext(request("xianyu", CONV, message), state);
    }

    private RoutingContext routing(String tenantId, String conversationId, String message) {
        return new RoutingContext(request(tenantId, conversationId, message),
                new ConversationState(message, "USER-XY-TEST"));
    }

    private ChatRequest request(String tenantId, String conversationId, String message) {
        return new ChatRequest(tenantId, conversationId, "USER-XY-TEST", message,
                AuthenticatedPrincipal.unverifiedClaim("USER-XY-TEST"));
    }

    /** 执行一轮议价响应并返回直发买家的确定性 AskUserAction 话术。 */
    private AskUserAction respond(BargainWorkflow workflow, ConversationState state, String message) {
        return assertInstanceOf(AskUserAction.class, workflow.nextAction(turn(state, message)).getAction());
    }

    /** 执行一轮议价响应并断言其结果是暂停转卖家的 HandoffAction。 */
    private HandoffAction handoff(BargainWorkflow workflow, ConversationState state, String message) {
        return assertInstanceOf(HandoffAction.class, workflow.nextAction(turn(state, message)).getAction());
    }

    /** 隔离知识库的闲鱼商品事实桩：商品身份与会话校验行为与生产一致，事实可热替换以模拟改价/售出。 */
    private static final class StubItemFactService extends XianyuItemFactService {

        private ItemFacts facts;

        StubItemFactService(ItemFacts facts) {
            super(null, null, new XianyuSidecarProperties());
            this.facts = facts;
        }

        void setFacts(ItemFacts facts) {
            this.facts = facts;
        }

        @Override
        public Optional<ItemFacts> currentItemFacts(String conversationId) {
            return facts == null ? Optional.empty() : Optional.of(facts);
        }

        @Override
        public boolean isXianyuConversation(String tenantId, String conversationId) {
            return "xianyu".equalsIgnoreCase(tenantId)
                    && conversationId != null
                    && conversationId.startsWith("xianyu_");
        }
    }

    /** 路由对照用的低分兜底 Workflow。 */
    private static final class FallbackWorkflow implements AgentWorkflow {

        private final WorkflowDescriptor descriptor =
                new WorkflowDescriptor("fallback", "1.0.0", "测试兜底", 1);

        @Override
        public WorkflowDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public WorkflowMatchResult match(RoutingContext context) {
            return new WorkflowMatchResult(0.1, "测试兜底");
        }

        @Override
        public WorkflowDecision nextAction(TurnContext context) {
            return WorkflowDecision.next(new FinalAnswerAction("测试兜底"), "测试兜底");
        }
    }


}
