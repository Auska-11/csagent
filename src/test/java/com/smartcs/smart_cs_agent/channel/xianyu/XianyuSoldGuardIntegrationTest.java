package com.smartcs.smart_cs_agent.channel.xianyu;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
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
import com.smartcs.smart_cs_agent.workflow.productadvisory.ProductAdvisoryWorkflow;
import com.smartcs.smart_cs_agent.workflow.troubleshooting.TroubleshootingWorkflow;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 五轮修复的 Runtime 级验证：已售商品面对购买/议价/成交确认，无论 Router 把消息
 * 分给商品咨询还是通用兜底（议价路由已有 BargainWorkflow 硬拦截），买家都必须
 * 在代码层收到“明确告知已售”的回复——模型桩故意返回“价格需要和卖家确认”，
 * 任何一条路由都不许把它送到买家。反过来，已售会话的售后求助仍要正常排查，
 * 在售商品照常回答价格。
 *
 * <p>六轮补强：售后词不再整句一票否决——“有保修吗？多少钱”“好漂亮啊，没有问题
 * 我就要了”这类带明确问价/购买意图的消息即使混着政策词或否定故障表达，也必须
 * 命中守卫；明确放弃与退货退款仍交回原流程。</p>
 *
 * <p>七轮补强：问价先问对象——价格词紧跟维修/换货等服务动作时问的是已购商品的
 * 服务费用，不是商品售价，不得回已售；“有保修吗？多少钱”的独立问价从句与
 * “维修多少钱”的服务计价从句必须同时成立，一边修复不得弄坏另一边。</p>
 *
 * <p>八轮收紧：服务计价只认封闭连接词连接的明确表达（“修一下要多少钱”），
 * 维修历史（“维修过的这款耳机多少钱”）与独立购买信号（“维修过也行我想买
 * 这件多少钱”）不得被整段吞掉——这两条售前请求与七轮的维修费用求助同组
 * 互锁，三边必须同时成立。</p>
 */
class XianyuSoldGuardIntegrationTest {

    private static final String BUYER = "sold-guard-buyer";
    private static final String ITEM = "1079979582985";
    private static final String CONV = XianyuIdentity.conversationId(BUYER, ITEM);
    private static final String USER_ID = XianyuIdentity.syntheticUserId(BUYER);

    /** 模型桩回复评审复现的缺陷话术：一旦守卫失效，断言能立刻抓到。 */
    private static final String DRIFTING_REPLY = "价格需要和卖家本人确认一下哦。";

    private static final ItemFacts SOLD_FACTS = new ItemFacts(
            ITEM,
            "森海塞尔MOMENTUM二代头戴耳机",
            PriceRange.of(new BigDecimal("265")),
            0,
            "森海塞尔 MOMENTUM 2 代头戴式蓝牙耳机。",
            "功能未测试；使用痕迹"
    );

    private static final ItemFacts IN_STOCK_FACTS = new ItemFacts(
            ITEM,
            "森海塞尔MOMENTUM二代头戴耳机",
            PriceRange.of(new BigDecimal("265")),
            1,
            "森海塞尔 MOMENTUM 2 代头戴式蓝牙耳机。",
            "功能未测试；使用痕迹"
    );

    @Test
    void soldOutPurchaseIntentInProductAdvisoryRouteGetsCodeLevelSoldReply() {
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("还在吗"));
        assertEquals("product_advisory", routedWorkflow(response),
                "“还在吗”必须先路由到商品咨询（守卫在该路由生效）");
        assertTrue(response.getReply().contains("售出"),
                "购买意图命中的是代码层售出话术: " + response.getReply());
        assertFalse(response.getReply().contains(DRIFTING_REPLY), "模型输出不得越过守卫直达买家");
        assertFalse(response.getReply().contains("265"), "已售不得再报挂牌价");
        assertFalse(response.getReply().contains("拍下"), "已售不得引导拍下");
        assertEquals(WorkflowStatus.COMPLETED,
                fixture.conversationStore().get(key()).getWorkflowStatus());
    }

    @Test
    void soldOutDealIntentInGeneralFallbackRouteGetsCodeLevelSoldReply() {
        // 排除商品咨询模拟路由飘移：未授权议价时“250可以成交”不命中议价词表，
        // 落到通用兜底——评审复现的正是这条路由回出“价格需要卖家确认”。
        LinkedHashSet<String> excluded = new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds());
        excluded.add("product_advisory");
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, XianyuScenarioProperties.of(excluded,
                new XianyuScenarioProperties.SellerRules()));

        ChatResponse response = fixture.agent().chatWithTrace(request("250可以成交"));
        assertEquals("customer_service", routedWorkflow(response),
                "该消息必须落到通用兜底（守卫在该路由生效）");
        assertTrue(response.getReply().contains("售出"), "兜底路由同样代码层告知售出: "
                + response.getReply());
        assertFalse(response.getReply().contains(DRIFTING_REPLY));
        assertFalse(response.getReply().contains("¥250"), "已售不得按买家出价承诺成交");
        assertEquals(WorkflowStatus.COMPLETED,
                fixture.conversationStore().get(key()).getWorkflowStatus());
    }

    @Test
    void soldOutBargainRouteStillBlockedByBargainWorkflowHardGate() {
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, authorizedScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("能便宜点吗"));
        assertEquals("bargain", routedWorkflow(response));
        assertTrue(response.getReply().contains("售出"),
                "议价路由与守卫同口径，三路由话术一致: " + response.getReply());
    }

    @Test
    void soldOutWarrantyPriceQuestionInFallbackGetsCodeLevelSoldReply() {
        // 六轮评审复现 1：“有保修吗？多少钱”——“保修”让商品咨询按政策问题让位，
        // 消息落通用兜底；带明确问价意图时必须命中守卫，缺陷话术不得直达买家。
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("有保修吗？多少钱"));
        assertEquals("customer_service", routedWorkflow(response),
                "“保修”触发商品咨询政策排除与排查词表未命中，落通用兜底");
        assertTrue(response.getReply().contains("售出"),
                "问价意图被守卫拦截，代码层告知已售: " + response.getReply());
        assertFalse(response.getReply().contains(DRIFTING_REPLY),
                "“价格需要卖家确认”不得再原样到达买家");
        assertFalse(response.getReply().contains("265"), "已售不得报挂牌价");
        assertEquals(WorkflowStatus.COMPLETED,
                fixture.conversationStore().get(key()).getWorkflowStatus());
    }

    @Test
    void soldOutNegatedFaultPurchaseInFallbackGetsCodeLevelSoldReply() {
        // 六轮评审复现 2：“好漂亮啊，没有问题我就要了”——社交词触发商品咨询排除，
        // “没有问题”不得被子串匹配成“有问题”而放行，购买意图必须命中守卫。
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("好漂亮啊，没有问题我就要了"));
        assertEquals("customer_service", routedWorkflow(response),
                "社交词触发商品咨询排除，消息落通用兜底");
        assertTrue(response.getReply().contains("售出"),
                "否定故障表达不掩盖购买意图，代码层告知已售: " + response.getReply());
        assertFalse(response.getReply().contains(DRIFTING_REPLY),
                "缺陷话术不得越过守卫直达买家");
        assertEquals(WorkflowStatus.COMPLETED,
                fixture.conversationStore().get(key()).getWorkflowStatus());
    }

    @Test
    void soldOutRepairHistoryItemPriceStillGetsCodeLevelSoldReply() {
        // 八轮评审复现 1：“维修过的这款耳机多少钱”——“维修过”是历史修饰，问价对象
        // 是商品售价；服务计价剥离只认封闭连接词，不得吞掉价格词。
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("维修过的这款耳机多少钱"));
        assertEquals("product_advisory", routedWorkflow(response),
                "维修历史问价照常落商品咨询");
        assertTrue(response.getReply().contains("售出"),
                "商品问价必须命中守卫，代码层告知已售: " + response.getReply());
        assertFalse(response.getReply().contains(DRIFTING_REPLY),
                "缺陷话术不得越过守卫直达买家");
        assertFalse(response.getReply().contains("265"), "已售不得报挂牌价");
        assertEquals(WorkflowStatus.COMPLETED,
                fixture.conversationStore().get(key()).getWorkflowStatus());
    }

    @Test
    void soldOutRepairHistoryWithExplicitPurchaseStillGetsCodeLevelSoldReply() {
        // 八轮评审复现 2：“维修过也行我想买这件多少钱”——句中带“维修”不改变
        // “我想买”+问价的购买推进性质，独立购买信号不得被计价剥离吞掉。
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("维修过也行我想买这件多少钱"));
        assertEquals("product_advisory", routedWorkflow(response),
                "无议价/政策/社交词，落商品咨询");
        assertTrue(response.getReply().contains("售出"),
                "明确购买意图必须命中守卫: " + response.getReply());
        assertFalse(response.getReply().contains(DRIFTING_REPLY),
                "缺陷话术不得越过守卫直达买家");
        assertEquals(WorkflowStatus.COMPLETED,
                fixture.conversationStore().get(key()).getWorkflowStatus());
    }

    @Test
    void soldOutRemediationCostQuestionStillAnsweredNotSoldReply() {
        // 七轮评审复现：“我收到的耳机坏了，维修多少钱”——已经买到商品后的维修
        // 费用咨询，问价对象是维修不是商品，必须照常走原流程，不得拿售出状态挡回。
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("我收到的耳机坏了，维修多少钱"));
        assertEquals("product_advisory", routedWorkflow(response),
                "泛化“坏了”不命中排查词表，照常落商品咨询");
        assertFalse(response.getReply().contains("售出"),
                "维修计价不是购买推进，不得回已售: " + response.getReply());
        assertEquals(DRIFTING_REPLY, response.getReply(), "照常走模型生成");
    }

    @Test
    void soldOutAfterSaleHelpStillGoesTroubleshootingNotSoldReply() {
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("耳机跟我手机连不上蓝牙怎么办"));
        assertEquals("troubleshooting", routedWorkflow(response),
                "售后求助必须照常路由排查，不因已售一律回售出");
        assertFalse(response.getReply().contains("售出"));
        assertTrue(response.getReply().contains("排查"), "排查流程照常追问: "
                + response.getReply());
    }

    @Test
    void soldOutChitchatStillGoesGeneralFallbackNotSoldReply() {
        StubItemFactService facts = new StubItemFactService(SOLD_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        // “颜值真高”被商品咨询的社交闲聊双信号排除，落到通用兜底。
        ChatResponse response = fixture.agent().chatWithTrace(request("颜值真高"));
        assertEquals("customer_service", routedWorkflow(response));
        assertFalse(response.getReply().contains("售出"), "闲聊不得一律回已售");
        assertEquals(DRIFTING_REPLY, response.getReply(), "闲聊照常走模型生成");
    }

    @Test
    void inStockPurchaseQuestionStillAnswersNormally() {
        StubItemFactService facts = new StubItemFactService(IN_STOCK_FACTS);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("多少钱"));
        assertEquals("product_advisory", routedWorkflow(response));
        assertEquals(DRIFTING_REPLY, response.getReply(), "在售商品照常走模型回答");
        assertFalse(response.getReply().contains("售出"));
    }

    @Test
    void soldFactsMissingFallsBackToUnknownNotSold() {
        // 事实未同步（知识文档未入库、也无热更新）：不得凭空说已售，照常走原流程。
        StubItemFactService facts = new StubItemFactService(null);
        TestFixture fixture = fixture(facts, defaultScenario());

        ChatResponse response = fixture.agent().chatWithTrace(request("还在吗"));
        assertFalse(response.getReply().contains("售出"),
                "库存未知时守卫不得拦截或提售出: " + response.getReply());
    }

    private XianyuScenarioProperties defaultScenario() {
        return XianyuScenarioProperties.of(
                new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds()),
                new XianyuScenarioProperties.SellerRules());
    }

    private TestFixture fixture(StubItemFactService factService, XianyuScenarioProperties scenario) {
        BargainWorkflow bargain = new BargainWorkflow(new GlobalInterruptPolicy(), factService, scenario);
        GeneralCustomerServiceWorkflow fallback = new GeneralCustomerServiceWorkflow(factService);
        ProductAdvisoryWorkflow advisory = new ProductAdvisoryWorkflow(
                new GlobalInterruptPolicy(), new com.smartcs.smart_cs_agent.memory.UserMemoryStore(), factService);
        TroubleshootingWorkflow troubleshooting = new TroubleshootingWorkflow(new GlobalInterruptPolicy());

        WorkflowRegistry registry = new WorkflowRegistry(
                List.of(bargain, fallback, advisory, troubleshooting, new StubOrderQueryWorkflow()), List.of());
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
                        skillManager, new FixedReplyChatLanguageModel(), WorkflowTestSupport.localCapabilityGateway()));
        ConversationStore conversationStore = new ConversationStore(new InMemorySessionStore());
        var agent = new CustomerServiceAgent(runtime, conversationStore);
        return new TestFixture(agent, conversationStore);
    }

    private XianyuScenarioProperties authorizedScenario() {
        XianyuScenarioProperties.SellerRules rules = new XianyuScenarioProperties.SellerRules();
        rules.setBargainAccepted(true);
        rules.setFloorPrice(new BigDecimal("240"));
        rules.setAllowedDiscount(new BigDecimal("20"));
        return XianyuScenarioProperties.of(
                new LinkedHashSet<>(XianyuScenarioProperties.defaultExcludedWorkflowIds()), rules);
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

    private ConversationKey key() {
        return ConversationKey.from(request("x"));
    }

    private String routedWorkflow(ChatResponse response) {
        return response.getTrace().stream()
                .filter(event -> "workflow_routed".equals(event.getEventType()))
                .map(event -> String.valueOf(event.getData().get("selectedWorkflow")))
                .findFirst().orElse(null);
    }

    private record TestFixture(CustomerServiceAgent agent, ConversationStore conversationStore) {
    }

    /** 隔离知识库的闲鱼商品事实桩：可热替换模拟改价/售出。 */
    private static final class StubItemFactService extends XianyuItemFactService {

        private ItemFacts facts;

        StubItemFactService(ItemFacts facts) {
            super(null, new XianyuSidecarProperties());
            this.facts = facts;
        }

        @Override
        public java.util.Optional<ItemFacts> currentItemFacts(String conversationId) {
            return java.util.Optional.ofNullable(facts);
        }
    }

    /** 模型桩只返回缺陷话术：守卫生效时它到不了买家，其它路径的输出也会被断言甄别。 */
    private static final class FixedReplyChatLanguageModel implements ChatLanguageModel {

        @Override
        public Response<AiMessage> generate(List<ChatMessage> messages) {
            return Response.from(AiMessage.from(DRIFTING_REPLY));
        }
    }

    /** 升级部署前的演示订单流程替身：确认闲鱼会话不会恢复被排除的流程。 */
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
