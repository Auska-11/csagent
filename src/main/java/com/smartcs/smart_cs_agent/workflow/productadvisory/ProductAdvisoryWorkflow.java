package com.smartcs.smart_cs_agent.workflow.productadvisory;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSearchResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSummary;
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
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 独立商品咨询与推荐 Workflow。
 *
 * <p>职责：</p>
 * <ul>
 *     <li>识别价格、库存、商品特点和推荐请求。</li>
 *     <li>用户没有说清商品时跨轮追问关键词。</li>
 *     <li>“推荐一下”没有关键词时，优先读取最近的长期商品偏好。</li>
 *     <li>调用 product.search，并用稳定 ProductSearchResult 组织回复。</li>
 * </ul>
 *
 * <p>不负责：不直接访问 ProductRepository，不修改长期偏好，也不承诺价格或库存永久有效。</p>
 */
@Component
public class ProductAdvisoryWorkflow implements AgentWorkflow {

    public static final String ID = ProductAdvisoryState.WORKFLOW_ID;
    public static final String VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "查询商品价格、库存、特点，并基于用户偏好提供目录内推荐",
            165
    );

    private final CustomerServiceSlotExtractor slotExtractor = new CustomerServiceSlotExtractor();
    private final CustomerServiceIntentRules intentRules = new CustomerServiceIntentRules();
    private final GlobalInterruptPolicy interruptPolicy;
    private final UserMemoryStore userMemoryStore;

    public ProductAdvisoryWorkflow(GlobalInterruptPolicy interruptPolicy,
                                   UserMemoryStore userMemoryStore) {
        this.interruptPolicy = interruptPolicy;
        this.userMemoryStore = userMemoryStore;
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    /**
     * 匹配只做打分，不读取 Repository 或执行 Capability。
     *
     * <p>偏好写入、政策、订单和退货请求主动让路，确保迁移期间原有行为不会被商品关键词抢走。</p>
     */
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

        return switch (state.getStep()) {
            case START -> start(context, state);
            case WAITING_KEYWORD -> collectKeyword(context, state);
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
        String keyword = slotExtractor.extractProductKeyword(context.getMessage());
        if (keyword == null) {
            state.transitionTo(ProductAdvisoryStep.WAITING_KEYWORD);
            return WorkflowDecision.next(
                    new AskUserAction("请告诉我想了解的商品或品类，例如耳机、手表、充电器或数据线。"),
                    "价格或库存问题缺少商品关键词"
            );
        }

        // 空字符串表示用户只说了“推荐一下”或“有什么商品”，此时先尝试长期偏好。
        if (keyword.isBlank()) {
            String preference = latestProductPreference(context);
            if (preference != null) {
                return search(state, preference, preference);
            }
        }
        return search(state, keyword, null);
    }

    private WorkflowDecision collectKeyword(TurnContext context, ProductAdvisoryState state) {
        String keyword = slotExtractor.extractProductKeyword(context.getMessage());
        if (keyword == null) {
            // 进入等待状态后，整条简短回复也可以作为搜索词。这样新增“键盘、鼠标”等品类时，
            // 不必先修改硬编码槽位词表，Provider 会用真实商品目录判断是否存在。
            keyword = context.getMessage() == null ? "" : context.getMessage().trim();
        }
        if (keyword.isBlank()) {
            return WorkflowDecision.next(
                    new AskUserAction("我还没有识别到商品名称，请补充一个商品或品类关键词。"),
                    "仍未取得商品关键词"
            );
        }
        return search(state, keyword, null);
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
