package com.smartcs.smart_cs_agent.workflow.policyqa;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.contracts.KnowledgeSearchHit;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 通用客服政策问答 Workflow。
 *
 * <p>它负责退换货规则、发票、配送和人工转接规则等“查制度”问题。用户要执行退货、查询具体
 * 订单、询问保修或明确要求人工时，分别让给对应 Workflow。</p>
 *
 * <p>回答只读取 knowledge.search 返回的稳定 KnowledgeSearchHit，不直接读取 Markdown 文件，
 * 也不根据模型常识补充知识库之外的承诺。</p>
 */
@Component
public class PolicyQaWorkflow implements AgentWorkflow {

    public static final String ID = PolicyQaState.WORKFLOW_ID;
    public static final String VERSION = "1.0.0";

    private static final Set<String> OWNED_DOCUMENTS = Set.of(
            "policy/return_policy.md",
            "policy/invoice_policy.md",
            "logistics/delivery_policy.md",
            "service/handoff_policy.md"
    );

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "回答退换货、发票、配送和人工转接规则等通用客服政策",
            178
    );

    private final CustomerServiceIntentRules intentRules = new CustomerServiceIntentRules();
    private final CustomerServiceSlotExtractor slotExtractor = new CustomerServiceSlotExtractor();
    private final GlobalInterruptPolicy interruptPolicy;

    public PolicyQaWorkflow(GlobalInterruptPolicy interruptPolicy) {
        this.interruptPolicy = interruptPolicy;
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        String message = normalize(context.getMessage());

        if (containsAny(message, "保修", "质保", "坏了能修", "故障能修", "warranty")) {
            return WorkflowMatchResult.noMatch("保修问题由 WarrantyQueryWorkflow 负责");
        }
        if (isExplicitHandoffRequest(message)) {
            return WorkflowMatchResult.noMatch("明确人工、投诉或赔偿请求应进入升级流程");
        }
        if (isConcreteReturnTask(message)) {
            return WorkflowMatchResult.noMatch("具体退换货办理或进度查询不属于政策问答");
        }
        if (isConcreteOrderQuery(message)) {
            return WorkflowMatchResult.noMatch("具体订单或物流查询应进入订单 Workflow");
        }
        if (isBroadPolicyQuestion(message)) {
            return new WorkflowMatchResult(0.96, "用户提出了泛化政策咨询，需要追问主题");
        }
        if (intentRules.isKnowledgeRelatedMessage(message)
                || isInvoiceQuestion(message)
                || isDeliveryPolicyQuestion(message)
                || isHandoffPolicyQuestion(message)) {
            return new WorkflowMatchResult(0.98, "命中退换货、发票、配送或人工转接规则表达");
        }
        return WorkflowMatchResult.noMatch("未命中通用政策问答表达");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        context.getState().setActiveIntent("knowledge_query");
        PolicyQaState state = new PolicyQaState(context.getState());

        WorkflowDecision interrupted = handleInterrupt(context, state);
        if (interrupted != null) {
            return interrupted;
        }

        return switch (state.getStep()) {
            case START -> start(context, state);
            case WAITING_TOPIC -> collectTopic(context, state);
            case SEARCHING -> handleSearchResult(context, state);
            case COMPLETED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次政策咨询已经完成。"),
                    "政策问答 Workflow 已完成"
            );
            case CANCELLED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次政策咨询已取消。"),
                    "政策问答 Workflow 已取消"
            );
            case FAILED -> technicalFailure("政策问答 Workflow 已失败");
        };
    }

    private WorkflowDecision start(TurnContext context, PolicyQaState state) {
        if (isBroadPolicyQuestion(normalize(context.getMessage()))) {
            state.transitionTo(PolicyQaStep.WAITING_TOPIC);
            return WorkflowDecision.next(
                    new AskUserAction("您想咨询哪类政策？可以问退换货规则、发票、配送或人工转接规则。"),
                    "泛化政策问题缺少具体主题"
            );
        }
        return search(state, context.getMessage());
    }

    private WorkflowDecision collectTopic(TurnContext context, PolicyQaState state) {
        String query = context.getMessage() == null ? "" : context.getMessage().trim();
        if (query.isBlank()) {
            return WorkflowDecision.next(
                    new AskUserAction("请补充想了解的政策主题，例如发票、配送或七天无理由退货。"),
                    "仍未取得政策主题"
            );
        }
        return search(state, query);
    }

    private WorkflowDecision search(PolicyQaState state, String query) {
        state.setQuery(query);
        state.transitionTo(PolicyQaStep.SEARCHING);
        return WorkflowDecision.next(
                new CapabilityAction(CapabilityIds.KNOWLEDGE_SEARCH, Map.of("query", query)),
                "检索正式客服知识库，避免根据常识猜测政策"
        );
    }

    private WorkflowDecision handleSearchResult(TurnContext context, PolicyQaState state) {
        CapabilityObservation result = latestCapabilityResult(context);
        if (result == null || !CapabilityIds.KNOWLEDGE_SEARCH.equals(result.getCapabilityId())) {
            state.transitionTo(PolicyQaStep.FAILED);
            return technicalFailure("没有获得预期的 knowledge.search 结果");
        }
        if (!result.isSuccess()) {
            state.transitionTo(PolicyQaStep.FAILED);
            return technicalFailure("知识检索未成功");
        }

        KnowledgeSearchHit hit = firstOwnedHit(result.getData());
        state.transitionTo(PolicyQaStep.COMPLETED);
        if (hit == null || hit.getSnippet().isBlank()) {
            return WorkflowDecision.next(
                    new FinalAnswerAction("客服知识库暂时没有找到明确规则。请补充问题细节，或联系人工客服确认。"),
                    "知识检索没有命中本 Workflow 负责的可靠政策"
            );
        }

        String title = hit.getTitle().isBlank() ? "客服政策" : hit.getTitle();
        String answer = "根据客服知识库的《" + title + "》：\n"
                + hit.getSnippet()
                + "\n\n以上是通用规则，具体订单或商品仍以页面信息和实际审核结果为准。";
        return WorkflowDecision.next(
                new FinalAnswerAction(answer),
                "使用最高相关度的归属政策条目生成确定性答复"
        );
    }

    private KnowledgeSearchHit firstOwnedHit(Object data) {
        if (!(data instanceof List<?> rows)) {
            return null;
        }
        for (Object row : rows) {
            if (row instanceof KnowledgeSearchHit hit && OWNED_DOCUMENTS.contains(hit.getDocumentId())) {
                return hit;
            }
        }
        return null;
    }

    private WorkflowDecision handleInterrupt(TurnContext context, PolicyQaState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(context.getMessage());
        if (interrupt.getType() == GlobalInterruptType.CANCEL) {
            state.transitionTo(PolicyQaStep.CANCELLED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("本次政策咨询已取消。"),
                    interrupt.getReason()
            );
        }
        if (interrupt.getType() == GlobalInterruptType.HANDOFF
                && !isHandoffPolicyQuestion(normalize(context.getMessage()))) {
            Map<String, Object> handoffContext = new LinkedHashMap<>(state.snapshot());
            state.transitionTo(PolicyQaStep.CANCELLED);
            return WorkflowDecision.next(
                    new HandoffAction(
                            interrupt.getHandoffReasonCode(),
                            HandoffSummarySupport.build("政策咨询", context, interrupt, handoffContext),
                            handoffContext
                    ),
                    interrupt.getReason()
            );
        }
        return null;
    }

    private WorkflowDecision technicalFailure(String reason) {
        return WorkflowDecision.next(
                new FinalAnswerAction("政策知识库暂时无法完成查询，请稍后重试或联系人工客服。"),
                reason
        );
    }

    private CapabilityObservation latestCapabilityResult(TurnContext context) {
        Observation observation = context.getLatestObservationByType("capability_result");
        return observation != null && observation.getData() instanceof CapabilityObservation result
                ? result
                : null;
    }

    private boolean isConcreteReturnTask(String message) {
        if (slotExtractor.extractReturnId(message) != null) {
            return true;
        }
        return containsAny(
                message,
                "我要退货", "申请退货", "我要退款", "申请退款", "我要换货", "申请换货",
                "退货进度", "退货状态", "退款进度", "退款状态"
        );
    }

    private boolean isConcreteOrderQuery(String message) {
        return slotExtractor.extractOrderId(message) != null
                || containsAny(message, "我的订单", "这个订单", "订单号", "查订单", "订单到哪");
    }

    private boolean isExplicitHandoffRequest(String message) {
        if (isHandoffPolicyQuestion(message)) {
            return false;
        }
        return containsAny(
                message,
                "我要人工", "转人工", "找真人", "真人客服", "我要投诉", "投诉你们",
                "我要赔偿", "要求赔偿", "维权", "12315", "律师", "起诉"
        );
    }

    private boolean isHandoffPolicyQuestion(String message) {
        return interruptPolicy.isHandoffPolicyQuestion(message);
    }

    private boolean isInvoiceQuestion(String message) {
        return containsAny(message, "发票", "开票", "专票", "抬头", "税号", "invoice");
    }

    private boolean isDeliveryPolicyQuestion(String message) {
        return containsAny(
                message,
                "配送", "偏远地区", "发货时效", "物流异常", "几天能送到", "多久能到", "什么时候送到",
                "delivery policy", "shipping policy"
        );
    }

    private boolean isBroadPolicyQuestion(String message) {
        return containsAny(message, "有什么政策", "咨询政策", "有哪些规则", "客服规则")
                && !intentRules.isKnowledgeRelatedMessage(message);
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
