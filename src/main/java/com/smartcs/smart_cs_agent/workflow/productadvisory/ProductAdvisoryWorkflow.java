package com.smartcs.smart_cs_agent.workflow.productadvisory;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.action.LLMAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSearchResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSummary;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService.ItemFacts;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuSoldItemGuard;
import com.smartcs.smart_cs_agent.handoff.sla.SlaIntegrationStore;
import com.smartcs.smart_cs_agent.handoff.sla.SlaVerificationQueryService;
import com.smartcs.smart_cs_agent.memory.UserMemorySnapshot;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.skill.CustomerServiceIntentRules;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSlotExtractor;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptType;
import com.smartcs.smart_cs_agent.workflow.core.HandoffSummarySupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 独立商品咨询 Workflow。
 *
 * <p>职责：</p>
 * <ul>
 *     <li>闲鱼会话优先解析“当前商品”（conversationId 携带的结构化 itemId），“这个/还在吗/
 *         多少钱”默认指当前商品，不再反问买家“想了解哪个商品”。</li>
 *     <li>数据选择与约束由代码完成（当前商品事实、未知价格不说 0、实况与型号能力分开），
 *         措辞交给 LLM——回复围绕买家当前的问题组织，而不是倾倒商品目录。</li>
 *     <li>演示租户保持原有目录检索行为（回归基线）。</li>
 * </ul>
 *
 * <p>不负责：不承诺议价（BargainWorkflow 负责）、不虚构知识库没有的实况。</p>
 */
@Component
public class ProductAdvisoryWorkflow implements AgentWorkflow {

    public static final String ID = ProductAdvisoryState.WORKFLOW_ID;
    public static final String VERSION = "2.0.0";

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "围绕买家当前问题回答商品价格、状态与描述；闲鱼会话默认当前商品",
            165
    );

    private final CustomerServiceSlotExtractor slotExtractor = new CustomerServiceSlotExtractor();
    private final CustomerServiceIntentRules intentRules = new CustomerServiceIntentRules();
    private final GlobalInterruptPolicy interruptPolicy;
    private final UserMemoryStore userMemoryStore;
    private final XianyuItemFactService xianyuItemFactService;
    private final SlaVerificationQueryService slaVerificationQueryService;

    @Autowired
    public ProductAdvisoryWorkflow(GlobalInterruptPolicy interruptPolicy,
                                   UserMemoryStore userMemoryStore,
                                   XianyuItemFactService xianyuItemFactService,
                                   SlaVerificationQueryService slaVerificationQueryService) {
        this.interruptPolicy = interruptPolicy;
        this.userMemoryStore = userMemoryStore;
        this.xianyuItemFactService = xianyuItemFactService;
        this.slaVerificationQueryService = slaVerificationQueryService;
    }

    /** 兼容旧测试：不注入 SLA 核实查询时，不消费核实结果也不建核实单。 */
    public ProductAdvisoryWorkflow(GlobalInterruptPolicy interruptPolicy,
                                   UserMemoryStore userMemoryStore,
                                   XianyuItemFactService xianyuItemFactService) {
        this(interruptPolicy, userMemoryStore, xianyuItemFactService, null);
    }

    /** 兼容旧测试：不注入闲鱼事实服务时退回目录检索行为。 */
    public ProductAdvisoryWorkflow(GlobalInterruptPolicy interruptPolicy,
                                   UserMemoryStore userMemoryStore) {
        this(interruptPolicy, userMemoryStore, null, null);
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        String message = normalize(context.getMessage());
        if (intentRules.isPreferenceMemoryMessage(message)) {
            return WorkflowMatchResult.noMatch("长期偏好写入暂由 Legacy 用户记忆分支处理");
        }
        if (intentRules.shouldHandoff(message)) {
            return WorkflowMatchResult.noMatch("投诉或升级请求暂交给 Legacy 人工升级分支");
        }
        if (intentRules.isKnowledgeRelatedMessage(message)) {
            return WorkflowMatchResult.noMatch("政策问题不属于商品目录咨询");
        }
        if (intentRules.isReturnRelatedMessage(message)) {
            return WorkflowMatchResult.noMatch("退换货任务不属于商品咨询");
        }
        if (containsAny(message, "订单", "物流", "快递", "发货", "order", "tracking")) {
            return WorkflowMatchResult.noMatch("订单和物流问题不属于商品咨询");
        }
        // 议价/砍价/包邮由 BargainWorkflow 接管，本 Workflow 只回答客观商品事实。
        if (isBargainMessage(message)) {
            return WorkflowMatchResult.noMatch("议价请求由 BargainWorkflow 处理");
        }
        if (isSocialSmalltalk(message)) {
            return WorkflowMatchResult.noMatch("夸赞/感叹类闲聊不属于商品咨询");
        }

        // 闲鱼会话：买家在当前商品语境里发的任何咨询（“还在吗”“这个支持蓝牙吗”“有什么瑕疵”）
        // 都直接命中——当前商品上下文使这些话天然有所指。
        if (isXianyuSession(context)) {
            return new WorkflowMatchResult(0.97, "闲鱼当前商品会话的商品咨询");
        }

        String keyword = slotExtractor.extractProductKeyword(message);
        if (keyword != null && !keyword.isBlank()) {
            return new WorkflowMatchResult(0.98, "命中明确商品品类关键词");
        }
        if (keyword != null) {
            return new WorkflowMatchResult(0.96, "命中商品目录或推荐表达");
        }
        if (containsAny(
                message,
                "多少钱", "价格", "有货", "库存", "推荐", "参数", "特点", "适合",
                "price", "stock", "recommend", "feature"
        )) {
            return new WorkflowMatchResult(0.93, "命中商品价格、库存或特点表达");
        }
        return WorkflowMatchResult.noMatch("未命中商品咨询表达");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        context.getState().setActiveIntent("product_query");
        ProductAdvisoryState state = new ProductAdvisoryState(context.getState());

        WorkflowDecision interrupted = handleInterrupt(context, state);
        if (interrupted != null) {
            return interrupted;
        }

        // LLM 已生成回复：本 Workflow 的正常终态。
        Observation llmResponse = context.getLatestObservationByType("llm_response");
        if (llmResponse != null) {
            state.transitionTo(ProductAdvisoryStep.COMPLETED);
            return WorkflowDecision.next(
                    new FinalAnswerAction(String.valueOf(llmResponse.getData())),
                    "商品咨询回复已由统一 LLMActionHandler 生成"
            );
        }

        return switch (state.getStep()) {
            case START, WAITING_KEYWORD -> start(context, state);
            case SEARCHING -> handleSearchResult(context, state);
            case COMPLETED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次商品咨询已经完成。"),
                    "商品咨询 Workflow 已完成"
            );
            case CANCELLED -> WorkflowDecision.next(
                    new CancelWorkflowAction("本次商品咨询已取消。"),
                    "商品咨询 Workflow 已取消"
            );
            case FAILED -> technicalFailure("商品咨询 Workflow 已失败");
        };
    }

    private WorkflowDecision start(TurnContext context, ProductAdvisoryState state) {
        // 闲鱼会话：结构化当前商品优先，绝不反问“想了解哪个商品”。
        Optional<ItemFacts> currentFacts = currentXianyuItemFacts(context);
        if (currentFacts.isPresent()) {
            ItemFacts facts = currentFacts.get();
            // 已售 + 购买/议价/成交确认意图：与 BargainWorkflow 同口径，代码直接告知
            // 售出，不再让“库存 0 件”经 LLM 自行翻译（曾飘成仍按挂牌价报价、
            // 引导确认成交）。商品咨询与售后求助照常走 answerWithCurrentItem。
            if (XianyuSoldItemGuard.isSoldOut(facts)
                    && XianyuSoldItemGuard.isDealAdvancingIntent(context.getMessage())) {
                state.transitionTo(ProductAdvisoryStep.COMPLETED);
                return WorkflowDecision.next(
                        new FinalAnswerAction(XianyuSoldItemGuard.SOLD_REPLY),
                        "商品已售，买家意在购买/议价/确认成交，直接告知售出"
                );
            }
            return answerWithCurrentItem(context, state, facts);
        }
        // 闲鱼会话但商品知识尚未入库（快照还在人工审校）：说明现状，不编造。
        if (isXianyuSession(context)) {
            state.transitionTo(ProductAdvisoryStep.COMPLETED);
            return WorkflowDecision.next(
                    new FinalAnswerAction(
                            "您好，这件商品的信息暂时还没有同步到我这里，"
                                    + "具体价格、成色和描述请以商品页面为准。"
                                    + "您也可以直接告诉我关心的问题（比如功能、成色、配件），"
                                    + "涉及价格确认的我会帮您转达卖家。"
                    ),
                    "当前商品知识文档未入库，按现状说明"
            );
        }

        // 演示租户：保持原目录行为。
        String keyword = slotExtractor.extractProductKeyword(context.getMessage());
        if (keyword == null) {
            state.transitionTo(ProductAdvisoryStep.WAITING_KEYWORD);
            return WorkflowDecision.next(
                    new AskUserAction("请告诉我想了解的商品或品类，例如耳机、手表、充电器或数据线。"),
                    "价格或库存问题缺少商品关键词"
            );
        }
        if (keyword.isBlank()) {
            String preference = latestProductPreference(context);
            if (preference != null) {
                return search(state, preference, preference);
            }
        }
        return search(state, keyword, null);
    }

    /**
     * 闲鱼当前商品回复：代码决定“能说什么”（当前商品事实、未知项的边界），
     * LLM 决定“怎么说”（围绕买家问题的措辞）。模型故障时 Handler 降级为
     * 通用客服话术，不会沉默。
     *
     * <p>SLA 核实闭环（契约第一阶段）也在这里收口：未测试功能被买家询问时创建
     * PRODUCT_CHECK 核实单并暂停自动回复；结果回调入库后，同一商品/买家/会话的
     * 后续提问按最新快照作答（CONFIRMED 引用结论，WITHDRAWN/UNABLE_TO_VERIFY
     * 禁止引用旧结论）。回调本身不触发这里的任何动作，只有买家再来提问才会消费。</p>
     */
    private WorkflowDecision answerWithCurrentItem(TurnContext context,
                                                   ProductAdvisoryState state,
                                                   ItemFacts facts) {
        state.transitionTo(ProductAdvisoryStep.COMPLETED);

        SlaIntegrationStore.ConversationVerification verification = latestVerification(context, facts);
        if (verification != null && verification.currentResult() == null
                && isVerificationRequest(context.getMessage(), facts)) {
            // 核实类追问等待结果；问价、库存等无关问题仍按当前商品事实回答。
            return WorkflowDecision.next(
                    new FinalAnswerAction(VERIFICATION_PENDING_REPLY),
                    "商品功能核实已在进行中，等待卖家提交结果"
            );
        }
        String verificationGuidance = verification == null
                ? null
                : slaVerificationQueryService.buildResultGuidance(verification);
        if (verification == null && isVerificationRequest(context.getMessage(), facts)) {
            // 首次对未测试功能提出核实类询问：创建卖家核实事项并暂停自动回复。
            // pauseOnly=false 走 Capability 网关，sla-v1 模式下由 HandoffTools 建
            // PRODUCT_CHECK 单；memory/after-sales-http 模式退化为普通工单，行为安全。
            Map<String, Object> handoffContext = new LinkedHashMap<>();
            handoffContext.put("itemId", facts.itemId());
            handoffContext.put("itemTitle", facts.title());
            handoffContext.put("buyerQuestion", context.getMessage() == null ? "" : context.getMessage());
            state.transitionTo(ProductAdvisoryStep.CANCELLED);
            return WorkflowDecision.next(
                    new HandoffAction(
                            HandoffAction.REASON_PRODUCT_VERIFICATION,
                            "买家询问商品功能是否正常，商品知识标记该功能未测试，需要卖家实际核实后答复。"
                                    + "商品：" + (facts.title().isBlank() ? facts.itemId() : facts.title())
                                    + "（itemId=" + facts.itemId() + "）",
                            handoffContext
                    ),
                    "买家询问未测试功能，创建卖家核实事项并暂停自动回复"
            );
        }

        return WorkflowDecision.next(
                new LLMAction(
                        "response_generation",
                        buildCurrentItemPrompt(context.getMessage(), facts, verificationGuidance),
                        Map.of(
                                "workflow", ID,
                                "itemId", facts.itemId(),
                                "priceKnown", facts.price().known(),
                                "stockKnown", facts.stockKnown(),
                                // 模型故障时按已知事实作答的确定性兜底，避免退回演示商城话术。
                                "fallbackReply", xianyuFallbackReply(facts)
                        )
                ),
                "闲鱼当前商品事实已取得，交给统一 LLMActionHandler 围绕买家问题作答"
        );
    }

    private SlaIntegrationStore.ConversationVerification latestVerification(TurnContext context,
                                                                            ItemFacts facts) {
        if (slaVerificationQueryService == null) {
            return null;
        }
        return slaVerificationQueryService.findLatestForItem(
                context.getRequest().getTenantId(),
                context.getRequest().getConversationId(),
                facts.itemId()
        ).orElse(null);
    }

    /** 核实进行中的确定性回复：不依赖模型，避免措辞漂移出“已请卖家核实”的口径。 */
    private static final String VERIFICATION_PENDING_REPLY =
            "您问的这个功能我这边还没有确切结论，已经帮您请卖家实际核实一下，"
                    + "结果确认后马上告诉您，请稍等。";

    /** 功能话题词：买家在问某个功能的实际状态，而不是泛泛了解商品。 */
    private static final List<String> FUNCTION_TOPIC_WORDS = List.of(
            "蓝牙", "连接", "配对", "通话", "降噪", "功能", "电池", "续航", "充电",
            "麦克风", "触控", "按键", "音质", "测试"
    );

    /** 核实类问法：买家想知道“这一件”该功能实际是否正常。 */
    private static final List<String> VERIFICATION_ASK_MARKERS = List.of(
            "正常", "能用", "可以用", "好用", "试过", "测试过", "测过", "没问题",
            "能连", "连不上", "连得上", "怎么样", "好不好", "行不行"
    );

    /**
     * 是否构成一次商品功能核实请求：本件实况标记“未测试” + 消息同时包含功能话题词
     * 和核实类问法。只认“未测试”标记——已测试/有瑕疵的实况按事实直接回答，不建单。
     */
    private boolean isVerificationRequest(String message, ItemFacts facts) {
        if (facts == null || !facts.condition().contains("未测试")) {
            return false;
        }
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) {
            return false;
        }
        return FUNCTION_TOPIC_WORDS.stream().anyMatch(text::contains)
                && VERIFICATION_ASK_MARKERS.stream().anyMatch(text::contains);
    }

    /** 与当前商品事实同口径的降级话术：只说已确认的价格/库存，未知项引导以商品页为准。 */
    private String xianyuFallbackReply(ItemFacts facts) {
        StringBuilder reply = new StringBuilder("关于这件商品：");
        if (!facts.title().isBlank()) {
            reply.append(facts.title()).append("，");
        }
        reply.append(facts.price().known() ? "挂牌 " + facts.price().display() : "价格以商品页为准")
                .append("，")
                .append(facts.stockKnown()
                        ? (facts.inStock() ? "目前有货" : "已经售出")
                        : "库存以商品页为准")
                .append("。其他细节您再问我，需要卖家确认的事我帮您转告。");
        return reply.toString();
    }

    private String buildCurrentItemPrompt(String message, ItemFacts facts) {
        return buildCurrentItemPrompt(message, facts, null);
    }

    private String buildCurrentItemPrompt(String message, ItemFacts facts, String verificationGuidance) {
        StringBuilder factsBlock = new StringBuilder();
        factsBlock.append("- 商品标题：")
                .append(facts.title().isBlank() ? "（未提供）" : facts.title()).append('\n');
        factsBlock.append("- 挂牌价：")
                .append(facts.price().known()
                        ? facts.price().display()
                        : "未知（知识文档未收录，只能建议买家以商品页为准）")
                .append('\n');
        factsBlock.append("- 库存：");
        if (facts.stockKnown() && facts.inStock()) {
            factsBlock.append(facts.stock()).append(" 件（二手单品通常为 1 件，在售即有货）").append('\n');
        } else if (facts.stockKnown()) {
            factsBlock.append("0 件（商品已经售出：不要报价、不要引导拍下或确认成交，"
                    + "涉及购买/议价时明确告知已售出）").append('\n');
        } else {
            factsBlock.append("未知（不得说“无货”，也不得虚构数量）").append('\n');
        }
        factsBlock.append("- 商品描述：")
                .append(facts.description().isBlank() ? "（未提供）" : facts.description()).append('\n');
        factsBlock.append("- 本件实况关键词：")
                .append(facts.condition().isBlank()
                        ? "未见记录（不得虚构成色或功能状态）"
                        : facts.condition())
                .append('\n');
        // SLA 核实结果块：只在有生效快照时注入；WITHDRAWN/UNABLE_TO_VERIFY 体现为
        // “禁止引用旧结论”的指令，而不是删除块——模型必须显式知道旧结论已不可用。
        String verificationSection = verificationGuidance == null
                ? ""
                : "卖家核实结果（代码层提供，最新版本）：\n" + verificationGuidance + "\n\n";

        return """
                你是这件闲鱼二手商品的客服助手，请回答买家关于【当前这一件商品】的问题。

                当前商品事实（代码层提供，唯一可信来源）：
                %s%s强制约束：
                1. 只回答买家当前问的问题，不要倾倒全部信息，不要罗列商品目录。
                2. 只能使用上述事实；事实里“未知”或“未见记录”的项，就明确说不掌握、
                   建议以商品页面为准，绝不编造数字或状态。
                3. “型号支持某功能”与“这一件的该功能已测试正常”是两回事：描述里写了
                   “功能未测试”就必须如实告知“功能未测试、建议到货后验证”，
                   不得说“蓝牙没问题”。若下方有卖家核实结果，则以核实结果为准。
                4. 买家问价格时按挂牌价回答；问“还在吗”按库存事实回答。
                5. 不主动议价、不承诺降价或包邮；涉及议价引导买家与卖家确认。
                6. 用中文、口语化、简洁（通常 1-3 句），不要用编号列表。

                买家消息：%s
                """.formatted(factsBlock, verificationSection, message == null ? "" : message);
    }

    private WorkflowDecision search(ProductAdvisoryState state,
                                    String keyword,
                                    String usedPreference) {
        state.setKeyword(keyword);
        state.setUsedPreference(usedPreference);
        state.transitionTo(ProductAdvisoryStep.SEARCHING);
        return WorkflowDecision.next(
                new CapabilityAction(CapabilityIds.PRODUCT_SEARCH, Map.of("keyword", keyword)),
                usedPreference == null
                        ? "按当前用户消息搜索商品目录"
                        : "当前消息没有商品词，按最近长期偏好搜索商品目录"
        );
    }

    private WorkflowDecision handleSearchResult(TurnContext context, ProductAdvisoryState state) {
        CapabilityObservation result = latestCapabilityResult(context);
        if (result == null || !CapabilityIds.PRODUCT_SEARCH.equals(result.getCapabilityId())) {
            state.transitionTo(ProductAdvisoryStep.FAILED);
            return technicalFailure("没有获得预期的 product.search 结果");
        }
        if (!result.isSuccess() || !(result.getData() instanceof ProductSearchResult products)) {
            state.transitionTo(ProductAdvisoryStep.FAILED);
            return technicalFailure("商品搜索技术失败或输出契约不正确");
        }

        state.transitionTo(ProductAdvisoryStep.COMPLETED);
        if (products.isEmpty()) {
            String keyword = state.getKeyword();
            String answer = keyword == null || keyword.isBlank()
                    ? "当前商品目录暂时为空，请稍后再试。"
                    : "暂时没有找到与“" + keyword + "”匹配的商品，您可以换一个名称或品类再咨询。";
            return WorkflowDecision.next(new FinalAnswerAction(answer), "商品目录没有匹配结果");
        }

        return WorkflowDecision.next(
                new FinalAnswerAction(formatProducts(products, state.getUsedPreference())),
                "商品搜索成功，使用稳定 ProductSearchResult 组织回复"
        );
    }

    private String formatProducts(ProductSearchResult result, String usedPreference) {
        StringBuilder answer = new StringBuilder();
        if (usedPreference != null && !usedPreference.isBlank()) {
            answer.append("根据您之前偏好的“").append(usedPreference).append("”，");
        }
        if (result.getKeyword().isBlank()) {
            answer.append("目前可咨询的商品有 ").append(result.getCount()).append(" 款：\n");
        } else {
            answer.append("找到 ").append(result.getCount())
                    .append(" 款与“").append(result.getKeyword()).append("”相关的商品：\n");
        }

        for (int i = 0; i < result.getProducts().size(); i++) {
            ProductSummary product = result.getProducts().get(i);
            answer.append(i + 1).append(". ")
                    .append(safeText(product.getName(), "未命名商品"))
                    .append("（").append(safeText(product.getProductId(), "无编号")).append("）")
                    .append("\n   价格：").append(product.getPrice()).append(" 元")
                    .append(" | 库存：")
                    .append(product.isInStock() ? product.getStock() + " 件" : "暂时无货");
            if (!product.getDescription().isBlank()) {
                answer.append("\n   特点：").append(product.getDescription());
            }
            if (i < result.getProducts().size() - 1) {
                answer.append("\n");
            }
        }
        answer.append("\n价格和库存以实际下单页面为准。");
        return answer.toString();
    }

    private Optional<ItemFacts> currentXianyuItemFacts(TurnContext context) {
        if (xianyuItemFactService == null) {
            return Optional.empty();
        }
        return xianyuItemFactService.currentItemFacts(context.getRequest().getConversationId());
    }

    private boolean isXianyuSession(RoutingContext context) {
        return xianyuItemFactService != null && xianyuItemFactService.isXianyuConversation(
                context.getRequest().getTenantId(),
                context.getRequest().getConversationId()
        );
    }

    private boolean isXianyuSession(TurnContext context) {
        return xianyuItemFactService != null && xianyuItemFactService.isXianyuConversation(
                context.getRequest().getTenantId(),
                context.getRequest().getConversationId()
        );
    }

    /** 议价/砍价/包邮信号：交给 BargainWorkflow，本 Workflow 只答客观事实。 */
    private boolean isBargainMessage(String message) {
        return containsAny(
                message,
                "便宜点", "便宜一点", "能便宜", "可以便宜", "少点", "少一点", "砍价", "议价",
                "最低多少", "最低价", "底价", "再让点", "让一点", "优惠", "打折", "折扣",
                "包邮", "免运费", "包个邮", "运费"
        );
    }

    private String latestProductPreference(TurnContext context) {
        String userId = context.getRequest().getUserId();
        UserMemorySnapshot snapshot = userMemoryStore.getSnapshot(userId);
        if (!snapshot.hasProductPreferences()) {
            return null;
        }

        String preference = snapshot.getProductPreferences().get(snapshot.getProductPreferences().size() - 1);
        Map<String, Object> traceData = new LinkedHashMap<>();
        traceData.put("userId", userId);
        traceData.put("preference", preference);
        traceData.put("allProductPreferences", snapshot.getProductPreferences());
        context.addTrace("user_memory_used", "商品推荐使用用户长期偏好", traceData);
        return preference;
    }

    private WorkflowDecision handleInterrupt(TurnContext context, ProductAdvisoryState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(context.getMessage());
        if (interrupt.getType() == GlobalInterruptType.CANCEL) {
            state.transitionTo(ProductAdvisoryStep.CANCELLED);
            return WorkflowDecision.next(
                    new CancelWorkflowAction("本次商品咨询已取消。"),
                    interrupt.getReason()
            );
        }
        if (interrupt.getType() == GlobalInterruptType.HANDOFF) {
            Map<String, Object> handoffContext = new LinkedHashMap<>(state.snapshot());
            state.transitionTo(ProductAdvisoryStep.CANCELLED);
            return WorkflowDecision.next(
                    new HandoffAction(
                            interrupt.getHandoffReasonCode(),
                            HandoffSummarySupport.build("商品咨询", context, interrupt, handoffContext),
                            handoffContext
                    ),
                    interrupt.getReason()
            );
        }
        return null;
    }

    private WorkflowDecision technicalFailure(String reason) {
        return WorkflowDecision.next(
                new FinalAnswerAction("商品目录暂时无法完成查询，请稍后重试或联系人工客服。"),
                reason
        );
    }

    private CapabilityObservation latestCapabilityResult(TurnContext context) {
        Observation observation = context.getLatestObservationByType("capability_result");
        return observation != null && observation.getData() instanceof CapabilityObservation result
                ? result
                : null;
    }

    /** 社交闲聊信号：夸赞、感叹、情绪表达。单独出现且无咨询标记时说明用户不是在查商品。 */
    private static final List<String> SOCIAL_MARKERS = List.of(
            "颜值", "好看", "漂亮", "真不错", "不错", "真棒", "喜欢", "哈哈", "点赞", "心情",
            "加油", "反应挺快", "服务真", "贴心", "可爱", "高级感", "质感"
    );

    /** 咨询信号：出现任意一个就说明用户在问商品事实，即使同时带社交词也不算闲聊。 */
    private static final List<String> ADVISORY_MARKERS = List.of(
            "多少钱", "价格", "有货", "库存", "推荐", "参数", "特点", "适合", "怎么选",
            "哪个", "区别", "对比", "介绍", "能", "可以", "有吗", "卖吗", "多少", "几块",
            "吗", "？", "?"
    );

    private boolean isSocialSmalltalk(String message) {
        boolean social = SOCIAL_MARKERS.stream().anyMatch(message::contains);
        if (!social) {
            return false;
        }
        return ADVISORY_MARKERS.stream().noneMatch(message::contains);
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
