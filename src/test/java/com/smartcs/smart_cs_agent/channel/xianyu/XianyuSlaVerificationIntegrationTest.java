package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.ItemFacts;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.PriceRange;
import com.smartcs.smart_cs_agent.config.SkillConfig;
import com.smartcs.smart_cs_agent.handoff.sla.InMemorySlaIntegrationStore;
import com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackPayload;
import com.smartcs.smart_cs_agent.handoff.sla.SlaResultDisposition;
import com.smartcs.smart_cs_agent.handoff.sla.SlaVerificationQueryService;
import com.smartcs.smart_cs_agent.handoff.sla.SlaWorkItemAssociation;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.session.memory.InMemorySessionStore;
import com.smartcs.smart_cs_agent.testing.WorkflowTestSupport;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.bargain.BargainWorkflow;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRegistry;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouter;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouterProperties;
import com.smartcs.smart_cs_agent.workflow.general.GeneralCustomerServiceWorkflow;
import com.smartcs.smart_cs_agent.workflow.productadvisory.ProductAdvisoryWorkflow;
import com.smartcs.smart_cs_agent.workflow.troubleshooting.TroubleshootingWorkflow;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SLA 商品核实闭环的 Runtime 级验证（契约 31 第一阶段）。
 *
 * <p>装配与 {@link XianyuSoldGuardIntegrationTest} 同构：真实 WorkflowRegistry /
 * WorkflowRouter / SkillManager / AgentRuntime / CustomerServiceAgent，只有商品事实与
 * LLM 用桩。SLA 集成 Store 用真实内存实现：测试先用 {@code recordAssociation} 模拟
 * “SLA v1 客户端建单成功后写入关联”，再用 {@code receive} 走与回调接收端完全相同的
 * 入库路径写入卖家结果——不绕过 Store 协议，也不直接调用被测的查询服务。</p>
 *
 * <p>模型桩回显收到的 Prompt：核实结论只通过 Prompt 注入生效，回显让断言直接看到
 * “买家再次询问时模型实际拿到了什么”，比断言最终回复更严格——措辞可以变，注入不能丢。</p>
 */
class XianyuSlaVerificationIntegrationTest {

    private static final String TENANT = "xianyu";
    private static final String BUYER = "22334455";
    private static final String ITEM = "1079979582986";
    private static final String OTHER_ITEM = "9999888877771";
    private static final String CONV = XianyuIdentity.conversationId(BUYER, ITEM);
    private static final String USER_ID = XianyuIdentity.syntheticUserId(BUYER);
    private static final String REQUEST_ID = "wi-v1-11223344556677889900aabbccddeeff"
            + "00112233445566778899aabbccddeeff";

    private static final String CONFIRMED_CONTENT = "卖家已实测，该耳机蓝牙可正常连接，通话清晰。";

    private static final ItemFacts UNTESTED_FACTS = new ItemFacts(
            ITEM,
            "森海塞尔MOMENTUM二代头戴耳机",
            PriceRange.of(new BigDecimal("265")),
            1,
            "森海塞尔 MOMENTUM 2 代头戴式蓝牙耳机。",
            "功能未测试；使用痕迹"
    );

    @Test
    void untestedFunctionQuestionCreatesVerificationHandoffThroughRealRuntime() {
        StubItemFactService facts = new StubItemFactService(UNTESTED_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("蓝牙连接正常吗"));

        assertEquals("product_advisory", routedWorkflow(response));
        assertTrue(response.isHandoffRequested(), "未测试功能的核实询问必须触发转核实，不是普通回答");
        assertTrue(response.getReply().contains("已经请卖家本人实际核实"),
                "买家话术必须是核实话术，不是道歉式转人工: " + response.getReply());
        assertTrue(response.getReply().contains("记录编号"), "话术要带核实记录编号: " + response.getReply());
        assertFalse(response.getReply().contains("转接人工客服"),
                "核实不是普通人工接管，不得使用道歉/转人工口径");
        assertEquals(HandoffAction.REASON_PRODUCT_VERIFICATION, handoffReason(response),
                "Trace 必须记录 PRODUCT_VERIFICATION 原因");
        assertEquals(WorkflowStatus.HANDED_OFF,
                fixture.conversationStore().get(key()).getWorkflowStatus());
    }

    @Test
    void confirmedSellerResultGuidesFollowUpQuestionThroughRealRuntime() {
        StubItemFactService facts = new StubItemFactService(UNTESTED_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());
        seedConfirmedResult(fixture.slaStore());

        ChatResponse response = fixture.agent().chatWithTrace(request("蓝牙连接正常吗"));

        assertEquals("product_advisory", routedWorkflow(response));
        assertFalse(response.isHandoffRequested(), "结果已回，同一问题不得再建核实单");
        assertTrue(response.getReply().contains("卖家已实际核实，结论如下"),
                "Prompt 必须注入核实结论区块: " + response.getReply());
        assertTrue(response.getReply().contains(CONFIRMED_CONTENT),
                "卖家提交的结论原文必须进入模型上下文: " + response.getReply());
        assertTrue(response.getReply().contains("仅对本商品、本买家、本会话生效"),
                "结论生效范围约束必须随结论一起注入");
        assertEquals(WorkflowStatus.COMPLETED,
                fixture.conversationStore().get(key()).getWorkflowStatus());
    }

    @Test
    void withdrawnSellerResultStopsCitingOldConclusion() {
        StubItemFactService facts = new StubItemFactService(UNTESTED_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());
        seedConfirmedResult(fixture.slaStore());
        // 更高版本的 WITHDRAWN 回调：与接收端相同的入库路径覆盖旧结论。
        SlaResultDisposition disposition = fixture.slaStore().receive(callbackPayload(
                "evt-sla-withdrawn", 2, "WITHDRAWN", "卖家撤回此前结论"));
        assertEquals(SlaResultDisposition.APPLIED, disposition);

        ChatResponse response = fixture.agent().chatWithTrace(request("蓝牙连接正常吗"));

        assertEquals("product_advisory", routedWorkflow(response));
        assertFalse(response.isHandoffRequested(), "撤回不等于重新建单，回调也不主动触发任何动作");
        assertTrue(response.getReply().contains("撤回"),
                "撤回后 Prompt 必须带上“结论已撤回”的禁引指令: " + response.getReply());
        assertTrue(response.getReply().contains("不得引用"),
                "禁引约束必须注入: " + response.getReply());
        assertFalse(response.getReply().contains(CONFIRMED_CONTENT),
                "被撤回的旧结论一个字都不得再进入模型上下文: " + response.getReply());
        assertFalse(response.getReply().contains("蓝牙可正常连接"));
    }

    @Test
    void pendingVerificationRepliesWaitingWithoutSecondWorkItem() {
        StubItemFactService facts = new StubItemFactService(UNTESTED_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());
        // 只有关联、没有结果：核实单在途（SLA v1 客户端建单成功但卖家还没提交）。
        fixture.slaStore().recordAssociation(association());

        ChatResponse response = fixture.agent().chatWithTrace(request("蓝牙连接正常吗"));

        assertEquals("product_advisory", routedWorkflow(response));
        assertFalse(response.isHandoffRequested(), "核实已在途，不得重复建单");
        assertTrue(response.getReply().contains("已经帮您请卖家实际核实"),
                "在途核实必须如实告知等待: " + response.getReply());
        assertTrue(response.getReply().contains("稍等"));
        assertEquals(0, response.getTrace().stream()
                        .filter(event -> "handoff_requested".equals(event.getEventType())).count(),
                "本轮不得再出现转人工/转核实 Trace");
        assertEquals(WorkflowStatus.COMPLETED,
                fixture.conversationStore().get(key()).getWorkflowStatus());
    }

    @Test
    void pendingVerificationDoesNotBlockPriceQuestion() {
        TestFixture fixture = fixture(new StubItemFactService(UNTESTED_FACTS), defaultScenario());
        fixture.slaStore().recordAssociation(association());

        ChatResponse response = fixture.agent().chatWithTrace(request("这件多少钱？"));

        assertEquals("product_advisory", routedWorkflow(response));
        assertFalse(response.isHandoffRequested());
        assertTrue(response.getReply().contains("¥265"), response.getReply());
        assertFalse(response.getReply().contains("已经帮您请卖家实际核实"), response.getReply());
    }

    @Test
    void resultForDifferentItemDoesNotApplyToCurrentItem() {
        StubItemFactService facts = new StubItemFactService(UNTESTED_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());
        // 结论登记在另一件商品名下：即便落在同一会话坐标，也不得用于当前商品。
        seedConfirmedResult(fixture.slaStore(), OTHER_ITEM);

        ChatResponse response = fixture.agent().chatWithTrace(request("蓝牙连接正常吗"));

        assertEquals("product_advisory", routedWorkflow(response));
        assertTrue(response.isHandoffRequested(),
                "跨商品结论不生效，当前商品仍按“未测试”重新发起核实");
        assertFalse(response.getReply().contains(CONFIRMED_CONTENT),
                "其它商品的结论不得泄漏进当前回答");
        assertEquals(HandoffAction.REASON_PRODUCT_VERIFICATION, handoffReason(response));
    }

    @Test
    void confirmedResultAlsoGuidesGeneralFallbackRoute() {
        StubItemFactService facts = new StubItemFactService(UNTESTED_FACTS);
        // 排除商品咨询模拟路由飘移：核实询问落到通用兜底，验证兜底 Prompt 同样注入结论。
        LinkedHashSet<String> excluded = new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds());
        excluded.add("product_advisory");
        TestFixture fixture = fixture(facts, XianyuScenarioProperties.of(excluded,
                new XianyuScenarioProperties.SellerRules()));
        seedConfirmedResult(fixture.slaStore());

        ChatResponse response = fixture.agent().chatWithTrace(request("蓝牙连接正常吗"));

        assertEquals("customer_service", routedWorkflow(response));
        assertFalse(response.isHandoffRequested());
        assertTrue(response.getReply().contains(CONFIRMED_CONTENT),
                "通用兜底的闲鱼 Prompt 也必须带上最新核实结论: " + response.getReply());
    }

    @Test
    void ordinaryComplaintHandoffKeepsOrdinaryWording() {
        StubItemFactService facts = new StubItemFactService(UNTESTED_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("我要投诉，太过分了"));

        assertEquals("complaint_escalation", routedWorkflow(response));
        assertTrue(response.isHandoffRequested());
        assertTrue(response.getReply().contains("转接人工客服"),
                "普通投诉仍是道歉式转人工口径: " + response.getReply());
        assertFalse(response.getReply().contains("实际核实"),
                "普通转人工不得使用核实话术");
        assertEquals("COMPLAINT", handoffReason(response));
    }

    private void seedConfirmedResult(InMemorySlaIntegrationStore slaStore) {
        seedConfirmedResult(slaStore, ITEM);
    }

    private void seedConfirmedResult(InMemorySlaIntegrationStore slaStore, String itemId) {
        slaStore.recordAssociation(association(itemId));
        SlaResultDisposition disposition = slaStore.receive(callbackPayload(
                "evt-sla-confirmed-" + itemId, 1, "CONFIRMED", CONFIRMED_CONTENT, itemId));
        assertEquals(SlaResultDisposition.APPLIED, disposition);
    }

    private XianyuScenarioProperties defaultScenario() {
        return XianyuScenarioProperties.of(
                new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds()),
                new XianyuScenarioProperties.SellerRules()
        );
    }

    private TestFixture fixture(StubItemFactService factService, XianyuScenarioProperties scenario) {
        InMemorySlaIntegrationStore slaStore = new InMemorySlaIntegrationStore();
        SlaVerificationQueryService verificationQueryService = new SlaVerificationQueryService(slaStore);
        BargainWorkflow bargain = new BargainWorkflow(new GlobalInterruptPolicy(), factService, scenario);
        GeneralCustomerServiceWorkflow fallback =
                new GeneralCustomerServiceWorkflow(factService, verificationQueryService);
        ProductAdvisoryWorkflow advisory = new ProductAdvisoryWorkflow(
                new GlobalInterruptPolicy(), new UserMemoryStore(), factService, verificationQueryService);
        TroubleshootingWorkflow troubleshooting = new TroubleshootingWorkflow(new GlobalInterruptPolicy());

        WorkflowRegistry registry = new WorkflowRegistry(
                List.of(bargain, fallback, advisory, troubleshooting, new StubComplaintWorkflow()), List.of());
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
                        skillManager, new PromptEchoChatLanguageModel(), WorkflowTestSupport.localCapabilityGateway()));
        ConversationStore conversationStore = new ConversationStore(new InMemorySessionStore());
        var agent = new CustomerServiceAgent(runtime, conversationStore);
        return new TestFixture(agent, conversationStore, slaStore);
    }

    private ChatRequest request(String message) {
        // 侧车真实链路会把合成买家身份登记为已认证主体；handoff.request 是副作用能力，
        // 网关要求已认证 Principal，未认证会被 AUTHENTICATION_REQUIRED 拒绝。
        return new ChatRequest(
                TENANT,
                CONV,
                USER_ID,
                message,
                AuthenticatedPrincipal.authenticated(TENANT, USER_ID, Set.of())
        );
    }

    private ConversationKey key() {
        return ConversationKey.from(request("x"));
    }

    private String routedWorkflow(ChatResponse response) {
        return response.getTrace().stream()
                .filter(event -> "workflow_routed".equals(event.getEventType()))
                .map(event -> String.valueOf(event.getData().get("selectedWorkflow")))
                .findFirst().orElse(null);
    }

    private String handoffReason(ChatResponse response) {
        return response.getTrace().stream()
                .filter(event -> "handoff_requested".equals(event.getEventType()))
                .map(event -> String.valueOf(event.getData().get("reason")))
                .findFirst().orElse(null);
    }

    /** 与真实回调同构的坐标：关联与结果载荷都必须对上当前买家/商品/会话。 */
    private static SlaWorkItemAssociation association() {
        return association(ITEM);
    }

    private static SlaWorkItemAssociation association(String itemId) {
        long now = System.currentTimeMillis();
        return new SlaWorkItemAssociation(
                TENANT,
                "agent-handoff-sla-it-" + itemId,
                REQUEST_ID,
                "XIANYU",
                "seller-sla-it",
                itemId,
                CONV,
                BUYER,
                null,
                now,
                now
        );
    }

    private static SlaResultCallbackPayload callbackPayload(String eventId,
                                                             int version,
                                                             String outcome,
                                                             String content) {
        return callbackPayload(eventId, version, outcome, content, ITEM);
    }

    private static SlaResultCallbackPayload callbackPayload(String eventId,
                                                             int version,
                                                             String outcome,
                                                             String content,
                                                             String itemId) {
        return new SlaResultCallbackPayload(
                1,
                eventId,
                "WORK_ITEM_RESULT_RECORDED",
                TENANT,
                99001L,
                REQUEST_ID,
                "XIANYU",
                "seller-sla-it",
                itemId,
                CONV,
                BUYER,
                new SlaResultCallbackPayload.Result(
                        version,
                        "CONVERSATION",
                        outcome,
                        content,
                        "seller-operator-1",
                        "2026-09-08T02:30:00Z"
                )
        );
    }

    private record TestFixture(CustomerServiceAgent agent,
                               ConversationStore conversationStore,
                               InMemorySlaIntegrationStore slaStore) {
    }

    /** 隔离知识库的闲鱼商品事实桩：成色固定含“未测试”标记。 */
    private static final class StubItemFactService extends XianyuItemFactService {

        private final ItemFacts facts;

        StubItemFactService(ItemFacts facts) {
            super(null, new XianyuSidecarProperties());
            this.facts = facts;
        }

        @Override
        public java.util.Optional<ItemFacts> currentItemFacts(String conversationId) {
            return java.util.Optional.ofNullable(facts);
        }
    }

    /** 回显最后一个用户消息（即 Workflow 组装的 Prompt）：断言直接看到模型拿到的上下文。 */
    private static final class PromptEchoChatLanguageModel implements ChatLanguageModel {

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            ChatMessage last = messages.get(messages.size() - 1);
            return Response.from(AiMessage.from(((UserMessage) last).singleText()));
        }
    }

    /** 普通投诉升级流程替身：与商品核实共用同一转人工链路，用于对比两类话术。 */
    private static final class StubComplaintWorkflow implements AgentWorkflow {

        private final WorkflowDescriptor descriptor =
                new WorkflowDescriptor("complaint_escalation", "1.0.0", "测试投诉升级", 5);

        @Override
        public WorkflowDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public WorkflowMatchResult match(RoutingContext context) {
            String message = context.getMessage() == null ? "" : context.getMessage();
            if (message.contains("投诉")) {
                return new WorkflowMatchResult(0.9, "测试投诉升级");
            }
            return WorkflowMatchResult.noMatch("非投诉消息不匹配");
        }

        @Override
        public WorkflowDecision nextAction(com.smartcs.smart_cs_agent.agent.context.TurnContext context) {
            return WorkflowDecision.next(
                    new HandoffAction("COMPLAINT", "买家明确投诉，需要人工处理", null),
                    "测试投诉升级转人工"
            );
        }
    }
}
