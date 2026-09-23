package com.smartcs.smart_cs_agent.workflow.general;

import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.LLMAction;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.ItemFacts;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSoldItemGuard;
import com.smartcs.smart_cs_agent.handoff.sla.SlaVerificationQueryService;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 通用客服兜底 Workflow。
 *
 * <p>职责：为闲聊、感谢和无法可靠归类的模糊输入生成简洁回复或澄清问题。</p>
 * <p>不负责：不查询订单、商品、退货、用户资料或政策，也不创建人工工单。上述请求必须由独立
 * Workflow 接管，避免通用 LLM 绕过能力授权和业务状态机。</p>
 *
 * <p>调用链：</p>
 * <pre>
 * WorkflowRouter 没有找到可靠的专业 Workflow
 *   -> GeneralCustomerServiceWorkflow
 *   -> LLMActionHandler 使用 customer-service Skill Package 组装 Prompt
 *   -> 本 Workflow 读取 llm_response Observation
 *   -> FinalAnswerAction
 * </pre>
 *
 * <p>稳定 ID 必须保持 {@code customer_service}，因为 Skill Package manifest 和已有会话都通过这个
 * ID 绑定 Workflow。Phase 9 只替换实现，不破坏这个外部契约。</p>
 *
 * <p>闲鱼会话的兜底 Prompt 单独组装：不提“订单、退换货”等模拟商城入口，
 * 改为围绕当前商品与卖家确认；有当前商品事实时注入结构化事实，
 * 避免“告诉我商品名/订单号”这类反问。</p>
 *
 * <p>已售商品的购买/议价/成交确认诉求在代码层直接告知售出
 * （{@link XianyuSoldItemGuard}），不交给模型发挥；闲聊与售后求助照常生成。</p>
 */
@Component
public class GeneralCustomerServiceWorkflow implements AgentWorkflow {

    public static final String ID = "customer_service";
    public static final String VERSION = "1.1.0";

    /**
     * 分数故意低于 Router 默认阈值 0.25。
     *
     * <p>这使它成为真正的 fallback：任何可靠的专业 Workflow 都可以胜出；如果所有候选都不可靠，
     * Router 再按 fallbackWorkflowId 明确选择本 Workflow。</p>
     */
    private static final double FALLBACK_SCORE = 0.20;

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "处理闲聊、感谢和无法可靠归类的通用客服输入",
            0
    );

    private final XianyuItemFactService xianyuItemFactService;
    private final SlaVerificationQueryService slaVerificationQueryService;

    @Autowired
    public GeneralCustomerServiceWorkflow(XianyuItemFactService xianyuItemFactService,
                                          SlaVerificationQueryService slaVerificationQueryService) {
        this.xianyuItemFactService = xianyuItemFactService;
        this.slaVerificationQueryService = slaVerificationQueryService;
    }

    /** 兼容旧测试：不注入 SLA 核实查询时 Prompt 与此前完全一致。 */
    public GeneralCustomerServiceWorkflow(XianyuItemFactService xianyuItemFactService) {
        this(xianyuItemFactService, null);
    }

    /** 兼容旧测试：不注入闲鱼事实服务时 Prompt 与此前完全一致。 */
    public GeneralCustomerServiceWorkflow() {
        this(null, null);
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        return new WorkflowMatchResult(FALLBACK_SCORE, "保留为通用客服兜底候选");
    }

    /**
     * 第一次进入时请求 LLM 生成回复；Handler 写入 Observation 后，Runtime 会在同一轮再次调用本方法，
     * 第二次再输出最终答案。Workflow 不直接调用模型，因此超时、降级和 Trace 仍由统一 Handler 管理。
     */
    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        Observation llmResponse = context.getLatestObservationByType("llm_response");
        if (llmResponse != null) {
            return WorkflowDecision.next(
                    new FinalAnswerAction(String.valueOf(llmResponse.getData())),
                    "通用客服回复已经生成"
            );
        }

        context.getState().setActiveIntent(isChitchat(context.getMessage()) ? "chitchat" : "other");

        Optional<ItemFacts> currentFacts = currentXianyuItemFacts(context);
        if (isXianyuSession(context)) {
            ItemFacts facts = currentFacts.orElse(null);
            // 已售商品的购买/议价/成交确认：代码层直接告知售出，不经 LLM——
            // “库存 0 件”写进 Prompt 后模型可能翻成“价格需要卖家确认”，
            // 买家会误以为还能购买。其他消息（闲聊、售后求助）照常生成。
            if (XianyuSoldItemGuard.isSoldOut(facts)
                    && XianyuSoldItemGuard.isDealAdvancingIntent(context.getMessage())) {
                return WorkflowDecision.next(
                        new FinalAnswerAction(XianyuSoldItemGuard.SOLD_REPLY),
                        "商品已售，买家意在购买/议价/确认成交，直接告知售出"
                );
            }
            return WorkflowDecision.next(
                    new LLMAction(
                            "response_generation",
                            buildXianyuPrompt(
                                    context.getMessage(),
                                    facts,
                                    verificationGuidance(context, facts)
                            ),
                            Map.of(
                                    "workflow", ID,
                                    "channel", "xianyu",
                                    // 模型故障时的闲鱼口径兜底：不得出现商城菜单（查订单/退货）演示话术。
                                    "fallbackReply",
                                    "不好意思，我这边刚才没跟上。您想了解这件商品的什么信息，"
                                            + "麻烦再发一次给我；需要卖家本人确认的事我会帮您转告。"
                            )
                    ),
                    "闲鱼兜底：围绕当前商品生成回复或单个澄清问题"
            );
        }

        return WorkflowDecision.next(
                new LLMAction(
                        "response_generation",
                        buildGeneralResponsePrompt(context.getMessage()),
                        Map.of("workflow", ID)
                ),
                "专业 Workflow 均未可靠命中，生成通用回复或澄清问题"
        );
    }

    /**
     * 闲鱼兜底 Prompt。事实块与商品咨询同源（XianyuItemFactService），
     * 但约束侧重“承认边界 + 只问一个最关键问题”。
     */
    private String buildXianyuPrompt(String message, ItemFacts facts) {
        return buildXianyuPrompt(message, facts, null);
    }

    private String buildXianyuPrompt(String message, ItemFacts facts, String verificationGuidance) {
        String factsBlock;
        if (facts != null) {
            factsBlock = """
                    当前商品事实（代码层提供，唯一可信来源）：
                    - 商品标题：%s
                    - 挂牌价：%s
                    - 库存：%s
                    - 商品描述：%s
                    - 本件实况关键词：%s
                    """.formatted(
                    facts.title().isBlank() ? "（未提供）" : facts.title(),
                    facts.price().known() ? facts.price().display() : "未知（建议买家以商品页为准）",
                    facts.stockKnown()
                            ? (facts.inStock() ? facts.stock() + " 件" : "0 件（商品已经售出）")
                            : "未知（不得说无货）",
                    facts.description().isBlank() ? "（未提供）" : facts.description(),
                    facts.condition().isBlank() ? "未见记录（不得虚构）" : facts.condition()
            );
            if (verificationGuidance != null) {
                // SLA 核实结果只对原商品/原买家/原会话生效；有生效快照时注入，
                // 撤回与无法核实体现为“禁止引用旧结论”的指令。
                factsBlock += "\n卖家核实结果（代码层提供，最新版本）：\n" + verificationGuidance + "\n";
            }
        } else {
            factsBlock = """
                    当前商品事实：暂未同步（不得编造价格、库存、成色；涉及具体事实建议买家以商品页为准）
                    """;
        }

        return """
                你是闲鱼卖家的客服助手，买家正在咨询卖家挂出的一件二手商品。请处理下面这条消息：

                %s
                回复要求：
                1. 闲聊、砍价之外的寒暄用中文友好、简洁地回应。
                2. 诉求不明确时，只追问一个最关键的问题（例如想了解功能、成色还是价格），
                   绝不同时罗列多个问题清单。
                3. 只能使用上面的商品事实；未知的事项如实说“不掌握，以商品页为准”。
                4. 不要引导买家去查订单、退换货、发票或官方客服电话——这是闲鱼个人卖家的
                   二手商品会话，没有这些流程；涉及需要卖家拍板的事（议价、发货方式、
                   以旧换新等）引导买家“稍等，我帮您转告卖家确认”。
                5. 不要编造价格、库存、物流、退款进度或平台政策，不要承诺降价或包邮。
                6. 商品已售出（库存 0 件）时：如实告知已售出、让买家看看卖家主页的
                   其他宝贝；不要报价、不要引导拍下或确认成交，也不要说“价格需要
                   卖家确认”。
                7. 售后问题（已购商品故障、连不上、退货等）不受售出影响，正常协助。

                买家消息：%s
                """.formatted(factsBlock, message == null ? "" : message);
    }

    private String buildGeneralResponsePrompt(String message) {
        return """
                请处理下面这条不需要调用业务工具的通用客服消息：
                - 如果是打招呼、感谢或礼貌闲聊，用中文友好、简洁地回复。
                - 如果诉求不明确，只追问一个最关键的问题，并提示可办理订单、商品、退换货、政策或人工客服。
                - 不要声称已经查询订单、修改数据、提交申请或创建工单。
                - 不要编造价格、库存、物流、退款进度或平台政策。

                用户消息：%s
                """.formatted(message == null ? "" : message);
    }

    private boolean isXianyuSession(TurnContext context) {
        return xianyuItemFactService != null && xianyuItemFactService.isXianyuConversation(
                context.getRequest().getTenantId(),
                context.getRequest().getConversationId()
        );
    }

    private Optional<ItemFacts> currentXianyuItemFacts(TurnContext context) {
        if (xianyuItemFactService == null) {
            return Optional.empty();
        }
        return xianyuItemFactService.currentItemFacts(context.getRequest().getConversationId());
    }

    /** SLA 核实结果说明；无事实、无注入服务或无生效快照时返回 null（Prompt 不变）。 */
    private String verificationGuidance(TurnContext context, ItemFacts facts) {
        if (slaVerificationQueryService == null || facts == null) {
            return null;
        }
        return slaVerificationQueryService
                .findLatestForItem(
                        context.getRequest().getTenantId(),
                        context.getRequest().getConversationId(),
                        facts.itemId()
                )
                .map(slaVerificationQueryService::buildResultGuidance)
                .orElse(null);
    }

    private boolean isChitchat(String message) {
        String text = message == null ? "" : message.trim().toLowerCase();
        return text.contains("你好")
                || text.contains("您好")
                || text.contains("谢谢")
                || text.contains("感谢")
                || text.equals("hi")
                || text.equals("hello");
    }
}
