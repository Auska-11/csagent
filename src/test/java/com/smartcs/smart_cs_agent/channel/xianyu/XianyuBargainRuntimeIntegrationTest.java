package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.ItemFacts;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.PriceRange;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.session.memory.InMemorySessionStore;
import com.smartcs.smart_cs_agent.testing.WorkflowTestSupport;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.bargain.BargainWorkflow;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRegistry;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouter;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouterProperties;
import com.smartcs.smart_cs_agent.workflow.general.GeneralCustomerServiceWorkflow;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闲鱼议价主链路的 Runtime 级集成测试：真实 Router / AgentRuntime / ActionHandler /
 * ConversationStore / XianyuManualModeService 全链路组装，模型用桩替换。
 *
 * <p>审阅结论要求测试不能只断言 Workflow 生成了什么 Action：这里每一轮都经过
 * CustomerServiceAgent.chatWithTrace 走完整执行循环，断言的是买家真正收到的回复、
 * 会话里的持久状态、trace 事件，以及人工模式对下一条消息的实际拦截。</p>
 */
class XianyuBargainRuntimeIntegrationTest {

    private static final String BUYER = "buyer-01";
    private static final String ITEM = "1079979582985";
    private static final String CONV = XianyuIdentity.conversationId(BUYER, ITEM);
    private static final String USER_ID = XianyuIdentity.syntheticUserId(BUYER);

    private static final ItemFacts FACTS = new ItemFacts(
            ITEM,
            "森海塞尔MOMENTUM二代头戴耳机",
            new PriceRange(new BigDecimal("265"), new BigDecimal("265")),
            1,
            "森海塞尔 MOMENTUM 2 代头戴式蓝牙耳机。",
            "功能未测试；使用痕迹"
    );

    @Test
    void waitingTurnPersistsQuoteAcrossRuntimeTurnsAndClosesOnAcknowledgment() {
        // 模型桩故意返回越权话术：议价全程不得调用它，买家收到的是代码确定性话术。
        TestFixture fixture = fixture(new FixedReplyChatLanguageModel("100元成交，已经改价，包邮。"),
                authorizedScenario(), new InMemorySessionStore());
        ConversationKey key = ConversationKey.from(request("240卖吗"));

        // 第一轮：报价 240 ≥ 底线 → 代码直接输出授权话术 → WAITING_USER（不是本轮 COMPLETED）。
        ChatResponse first = fixture.agent().chatWithTrace(request("240卖吗"));
        assertTrue(first.getReply().contains("¥240"), "买家收到的是代码授权话术: " + first.getReply());
        assertTrue(first.getReply().contains("拍下"), "引导拍下不付款、等卖家改价");
        assertTrue(hasTrace(first, "ask_user"));
        assertFalse(hasTrace(first, "llm_called"), "议价全程不经模型措辞");
        assertFalse(first.getReply().contains("100元"), "越权模型输出不得直达买家");
        assertFalse(first.getReply().contains("包邮"), "未授权的包邮承诺绝不出现");
        assertFalse(first.isHandoffRequested());

        ConversationState state = fixture.conversationStore().get(key);
        assertEquals("bargain", state.getActiveWorkflowId());
        assertEquals(WorkflowStatus.WAITING_USER, state.getWorkflowStatus());
        assertEquals("WAITING_BUYER", state.getWorkflowState("bargain", "step"));
        assertEquals("240", state.getWorkflowState("bargain", "lastQuote"));

        // 第二轮：买家继续砍价。Router 必须恢复（而不是重新路由）等待中的议价任务，
        // 已同意的 240 不得改口重报 245。
        ChatResponse second = fixture.agent().chatWithTrace(request("再便宜点"));
        assertTrue(second.getTrace().stream().anyMatch(event ->
                        "workflow_routed".equals(event.getEventType())
                                && Boolean.TRUE.equals(event.getData().get("resumed"))),
                "等待中的议价任务必须被粘性恢复");
        assertFalse(hasTrace(second, "llm_called"), "继续砍价同样不经模型");
        // 已同意 240 的重申与重新让价 245 的区别就落在买家话术：重申即证明没有改口。
        assertTrue(second.getReply().contains("¥240"), second.getReply());
        assertTrue(second.getReply().contains("没有再让的空间"), "连续砍价必须重申一口价");
        assertFalse(second.getReply().contains("245"));
        assertEquals("240", fixture.conversationStore().get(key).getWorkflowState("bargain", "lastQuote"),
                "已同意的价格不得在下一轮被改写（重报会变成 245）");

        // 第三轮：简短确认 → 收尾成交并重申价格，任务 COMPLETED。
        ChatResponse third = fixture.agent().chatWithTrace(request("好的"));
        assertTrue(third.getReply().contains("¥240"), "收尾必须重申已报价格: " + third.getReply());
        assertTrue(third.getReply().contains("拍下"), "引导拍下不付款、等卖家改价");
        assertEquals(WorkflowStatus.COMPLETED, fixture.conversationStore().get(key).getWorkflowStatus());
    }

    @Test
    void unauthorizedPauseActuallyHandsOffBlocksBuyerAndRecoversViaSellerMessage() {
        TestFixture fixture = fixture(new FixedReplyChatLanguageModel("不应被调用"),
                XianyuScenarioProperties.defaults(), new InMemorySessionStore());
        ConversationKey key = ConversationKey.from(request("250卖吗"));

        // 未授权议价：pauseOnly Handoff 经真实 Handler 执行，买家收到固定话术，
        // 会话终态 HANDED_OFF，且绝不创建工单（无 handoff.request 工具调用）。
        ChatResponse response = fixture.agent().chatWithTrace(request("250卖吗"));
        assertTrue(response.isHandoffRequested(), "pauseOnly 必须以 HANDED_OFF 收尾");
        assertTrue(response.getReply().contains("做不了主"), "买家话术: " + response.getReply());
        assertTrue(hasTrace(response, "handoff_pause_only"));
        assertFalse(response.getTrace().stream().anyMatch(event ->
                        "tool_called".equals(event.getEventType())
                                && String.valueOf(event.getData().get("capability")).contains("handoff")),
                "pauseOnly 不得创建人工工单");
        assertEquals(WorkflowStatus.HANDED_OFF, fixture.conversationStore().get(key).getWorkflowStatus());

        // HANDED_OFF 后服务端置人工模式（ChatApiController 同款调用），下一条买家消息被拦截。
        assertTrue(fixture.manualModeService().markManualAfterHandoff(request("250卖吗")));
        assertTrue(fixture.manualModeService().isBotPaused(request("还在吗，240卖吗")));
        assertTrue(fixture.manualModeService().manualMode(BUYER, ITEM).orElseThrow());

        // 卖家回复写回会话并恢复机器人：买家下一轮不再被拦截，消息原文进入会话历史。
        assertTrue(fixture.manualModeService().addSellerMessage(
                BUYER, ITEM, "240拿走，我已经改好价了", true));
        assertFalse(fixture.manualModeService().isBotPaused(request("还在吗")));
        ConversationState state = fixture.conversationStore().get(key);
        assertTrue(state.getMessages().stream().anyMatch(message ->
                        "assistant".equals(message.get("role"))
                                && String.valueOf(message.get("content")).contains("240拿走")),
                "卖家消息必须原样进入会话历史");
    }

    @Test
    void rogueOrBrokenModelNeverChangesTheAuthorizedQuoteBuyerReceives() {
        // 评审复现：模型桩返回“100元成交，已经改价，包邮”（代码授权 250）曾原样直达买家。
        // 现在议价话术由代码确定性生成，模型输出没有任何入口。
        TestFixture rogue = fixture(new FixedReplyChatLanguageModel("100元成交，已经改价，包邮。"),
                authorizedScenario(), new InMemorySessionStore());
        ChatResponse response = rogue.agent().chatWithTrace(request("250卖吗"));
        assertEquals("可以的，¥250 成交。您先拍下不要付款，我同步卖家改好价格您再支付。",
                response.getReply(), "买家收到的必须是代码授权话术原文");
        assertFalse(hasTrace(response, "llm_called"), "议价话术不经模型");
        assertFalse(response.getReply().contains("包邮"), "未授权的包邮承诺绝不出现");

        // 模型整体故障同样不影响议价：话术与模型可用性无关，也不退回演示商城文案。
        TestFixture outage = fixture(new ThrowingChatLanguageModel(), authorizedScenario(),
                new InMemorySessionStore());
        ChatResponse duringOutage = outage.agent().chatWithTrace(request("200卖吗"));
        assertTrue(duringOutage.getReply().contains("¥245"), "还价话术仍按授权计算: "
                + duringOutage.getReply());
        assertFalse(duringOutage.getReply().contains("查询订单"), "不得退回演示商城话术");
        assertFalse(duringOutage.getReply().contains("退货"));
        ConversationState state = outage.conversationStore().get(ConversationKey.from(request("200卖吗")));
        assertEquals(WorkflowStatus.WAITING_USER, state.getWorkflowStatus(),
                "授权话术以 AskUser 收尾，保持跨轮粘性");
        assertEquals("245", state.getWorkflowState("bargain", "lastQuote"));
    }

    @Test
    void soldOutAfterQuoteRefusesAcknowledgmentCloseAtStalePrice() {
        // 评审复现：报价 250 后库存热更新为 0，买家回“好的”仍按 ¥250 收尾。
        StubItemFactService facts = new StubItemFactService(FACTS);
        TestFixture fixture = fixture(new FixedReplyChatLanguageModel("ok"), authorizedScenario(),
                new InMemorySessionStore(), facts);
        ConversationKey key = ConversationKey.from(request("250卖吗"));

        ChatResponse quote = fixture.agent().chatWithTrace(request("250卖吗"));
        assertTrue(quote.getReply().contains("¥250"), "先给出授权报价: " + quote.getReply());

        facts.setFacts(new ItemFacts(
                ITEM, "森海塞尔MOMENTUM二代头戴耳机",
                new PriceRange(new BigDecimal("265"), new BigDecimal("265")),
                0, "", ""));
        ChatResponse ack = fixture.agent().chatWithTrace(request("好的"));
        assertTrue(ack.getReply().contains("售出"), "库存为 0 必须告知售出: " + ack.getReply());
        assertFalse(ack.getReply().contains("¥250"), "售出后不得再按旧报价承诺成交");
        assertFalse(ack.getReply().contains("拍下"), "无货不得再引导拍下");
        assertEquals(WorkflowStatus.COMPLETED, fixture.conversationStore().get(key).getWorkflowStatus());
    }

    @Test
    void revokedBargainAuthorityTurnsAcknowledgmentIntoSellerPause() {
        // 评审复现：撤销议价授权后买家确认，仍按旧价收尾。
        XianyuScenarioProperties.SellerRules rules = new XianyuScenarioProperties.SellerRules();
        rules.setBargainAccepted(true);
        rules.setFloorPrice(new BigDecimal("240"));
        rules.setAllowedDiscount(new BigDecimal("20"));
        XianyuScenarioProperties scenario = XianyuScenarioProperties.of(
                new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds()), rules);
        TestFixture fixture = fixture(new FixedReplyChatLanguageModel("ok"), scenario,
                new InMemorySessionStore());
        ConversationKey key = ConversationKey.from(request("250卖吗"));

        ChatResponse quote = fixture.agent().chatWithTrace(request("250卖吗"));
        assertTrue(quote.getReply().contains("¥250"));

        rules.setBargainAccepted(false); // 卖家在会话中途撤销议价授权
        ChatResponse ack = fixture.agent().chatWithTrace(request("好的"));
        assertTrue(ack.isHandoffRequested(), "授权撤销后确认必须转卖家本人，而不是按旧价成交");
        assertTrue(ack.getReply().contains("卖家本人"), "买家侧必须说明转卖家确认: " + ack.getReply());
        assertFalse(ack.getReply().contains("¥250"), "不得再按撤销授权前的旧价承诺");
        assertEquals(WorkflowStatus.HANDED_OFF, fixture.conversationStore().get(key).getWorkflowStatus());
    }

    @Test
    void renewedLowerOfferNeverRaisesAboveAgreedQuote() {
        // 评审复现：第一轮已按 240 同意，第二轮“200卖吗”又重新报出公式价 245。
        TestFixture fixture = fixture(new FixedReplyChatLanguageModel("ok"), authorizedScenario(),
                new InMemorySessionStore());
        ConversationKey key = ConversationKey.from(request("240卖吗"));
        fixture.agent().chatWithTrace(request("240卖吗"));

        ChatResponse second = fixture.agent().chatWithTrace(request("200卖吗"));
        assertTrue(second.getReply().contains("¥240"), "还价不得高于上一口价: " + second.getReply());
        assertTrue(second.getReply().contains("没有再让的空间"), "重申而不是重新让价");
        assertFalse(second.getReply().contains("245"), "不得重新报出更高的公式价");
        assertFalse(second.getReply().contains("¥200"), "低于已同意价的出价绝不被接受");
        assertEquals("240", fixture.conversationStore().get(key).getWorkflowState("bargain", "lastQuote"));
    }

    @Test
    void staleOrderQueryFromOldSessionIsNotResumedInXianyuConversation() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        TestFixture fixture = fixture(new FixedReplyChatLanguageModel("ok"), authorizedScenario(), sessionStore);

        // 预置一台“升级部署前”的旧会话：order_query 处于活跃未终态。
        ConversationKey key = ConversationKey.from(request("250卖吗"));
        ConversationState legacy = new ConversationState("帮我查订单", USER_ID);
        legacy.startOrResumeWorkflow("order_query", "1.0.0");
        fixture.conversationStore().save(key, legacy);

        ChatResponse response = fixture.agent().chatWithTrace(request("250卖吗"));
        ConversationState state = fixture.conversationStore().get(key);

        assertEquals("bargain", state.getActiveWorkflowId(),
                "被排除的演示流程不得粘性恢复，必须重路由到闲鱼议价");
        assertFalse(response.getReply().contains("订单号"), "买家不得收到演示订单号追问: " + response.getReply());
        assertFalse(response.getReply().contains("ORD-"));
        assertTrue(response.getTrace().stream().anyMatch(event ->
                        "workflow_routed".equals(event.getEventType())
                                && !Boolean.TRUE.equals(event.getData().get("resumed"))),
                "旧会话被取消后必须重新评分");
    }

    @Test
    void quoteMirrorAndManualModeSurviveProcessRestart() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        TestFixture firstProcess = fixture(new FixedReplyChatLanguageModel("240可以，您拍下。"),
                authorizedScenario(), sessionStore);
        ConversationKey key = ConversationKey.from(request("240卖吗"));

        firstProcess.agent().chatWithTrace(request("240卖吗"));
        assertTrue(firstProcess.manualModeService().markManualAfterHandoff(request("240卖吗")));

        // 模拟进程重启：同一持久化后端上重建整套 Agent/Router/Runtime。
        TestFixture secondProcess = fixture(new FixedReplyChatLanguageModel("ok"), authorizedScenario(),
                sessionStore);
        ConversationState restored = secondProcess.conversationStore().get(key);
        assertEquals("240", restored.getTaskState("bargain_last_quote"), "报价镜像必须跨重启存活");
        assertEquals("true", restored.getTaskState("xianyu_manual_mode"), "人工模式必须跨重启存活");
        assertTrue(secondProcess.manualModeService().isBotPaused(request("还在吗")));
        assertEquals(WorkflowStatus.WAITING_USER, restored.getWorkflowStatus());

        // 卖家确认后恢复机器人：重启后的进程仍按镜像价格收尾，不改口。
        secondProcess.manualModeService().addSellerMessage(BUYER, ITEM, "价格已同步", true);
        ChatResponse closed = secondProcess.agent().chatWithTrace(request("好的"));
        assertTrue(closed.getReply().contains("¥240"), "重启后收尾仍按已同意价格: " + closed.getReply());
        assertEquals(WorkflowStatus.COMPLETED, secondProcess.conversationStore().get(key).getWorkflowStatus());
    }

    /** 授权场景：接受议价、底线 240、单次让价 20（挂牌 265，还价 = 265-20 = 245）。 */
    private XianyuScenarioProperties authorizedScenario() {
        XianyuScenarioProperties.SellerRules rules = new XianyuScenarioProperties.SellerRules();
        rules.setBargainAccepted(true);
        rules.setFloorPrice(new BigDecimal("240"));
        rules.setAllowedDiscount(new BigDecimal("20"));
        return XianyuScenarioProperties.of(
                new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds()), rules);
    }

    private TestFixture fixture(ChatLanguageModel chatModel,
                                XianyuScenarioProperties scenario,
                                InMemorySessionStore sessionStore) {
        return fixture(chatModel, scenario, sessionStore, new StubItemFactService(FACTS));
    }

    private TestFixture fixture(ChatLanguageModel chatModel,
                                XianyuScenarioProperties scenario,
                                InMemorySessionStore sessionStore,
                                StubItemFactService factService) {
        BargainWorkflow bargain = new BargainWorkflow(new GlobalInterruptPolicy(), factService, scenario);
        GeneralCustomerServiceWorkflow fallback = new GeneralCustomerServiceWorkflow();

        WorkflowRegistry registry = new WorkflowRegistry(
                List.of(bargain, fallback, new StubOrderQueryWorkflow()), List.of());
        WorkflowRouterProperties routerProperties = new WorkflowRouterProperties();
        routerProperties.setFallbackWorkflowId(fallback.descriptor().getId());
        WorkflowRouter router = new WorkflowRouter(
                registry, routerProperties, new GlobalInterruptPolicy(), factService, scenario);

        SkillConfig skillConfig = new SkillConfig() {
            @Override
            public String getSystemPrompt() {
                return "你是测试环境中的闲鱼卖家客服助手。";
            }
        };
        var skillManager = new com.smartcs.smart_cs_agent.skill.SkillManager(
                skillConfig, null, router);

        var runtime = new com.smartcs.smart_cs_agent.agent.AgentRuntime(
                skillManager,
                WorkflowTestSupport.actionHandlerRegistry(
                        skillManager, chatModel, WorkflowTestSupport.localCapabilityGateway()));
        ConversationStore conversationStore = new ConversationStore(sessionStore);
        var agent = new CustomerServiceAgent(runtime, conversationStore);
        var manualModeService = new XianyuManualModeService(
                conversationStore, new XianyuSidecarProperties());
        return new TestFixture(agent, conversationStore, manualModeService);
    }

    private ChatRequest request(String message) {
        return new ChatRequest(
                "xianyu",
                CONV,
                USER_ID,
                message,
                AuthenticatedPrincipal.unverifiedClaim(USER_ID)
        );
    }

    private boolean hasTrace(ChatResponse response, String eventType) {
        return response.getTrace().stream()
                .anyMatch(event -> eventType.equals(event.getEventType()));
    }

    private record TestFixture(CustomerServiceAgent agent,
                               ConversationStore conversationStore,
                               XianyuManualModeService manualModeService) {
    }

    /** 隔离知识库的闲鱼商品事实桩。事实可热替换，模拟改价/售出热更新。 */
    private static final class StubItemFactService extends XianyuItemFactService {

        private ItemFacts facts;

        StubItemFactService(ItemFacts facts) {
            super(null, new XianyuSidecarProperties());
            this.facts = facts;
        }

        void setFacts(ItemFacts facts) {
            this.facts = facts;
        }

        @Override
        public java.util.Optional<ItemFacts> currentItemFacts(String conversationId) {
            return java.util.Optional.of(facts);
        }
    }

    /** 固定回复模型：隔离外部 LLM 的不确定性；可故意填越权话术，验证它到不了买家。 */
    private static final class FixedReplyChatLanguageModel implements ChatLanguageModel {

        private final String reply;

        private FixedReplyChatLanguageModel(String reply) {
            this.reply = reply;
        }

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            return Response.from(AiMessage.from(reply));
        }
    }

    /** 永远抛错的模型：模拟线上模型整体不可用。 */
    private static final class ThrowingChatLanguageModel implements ChatLanguageModel {

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            throw new IllegalStateException("model unavailable");
        }
    }

    /** 升级部署前的演示订单流程替身：粘性恢复测试只需要它的 ID 与“追问订单号”行为。 */
    private static final class StubOrderQueryWorkflow implements AgentWorkflow {

        private final com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor descriptor =
                new com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor("order_query", "1.0.0", "测试订单查询", 5);

        @Override
        public com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public WorkflowMatchResult match(RoutingContext context) {
            return new WorkflowMatchResult(0.9, "测试订单查询");
        }

        @Override
        public WorkflowDecision nextAction(com.smartcs.smart_cs_agent.agent.context.TurnContext context) {
            return WorkflowDecision.next(
                    new com.smartcs.smart_cs_agent.agent.action.AskUserAction(
                            "请提供 ORD-000 格式的订单号"),
                    "测试订单查询追问"
            );
        }
    }
}
