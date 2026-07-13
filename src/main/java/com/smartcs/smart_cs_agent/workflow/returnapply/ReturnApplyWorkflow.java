package com.smartcs.smart_cs_agent.workflow.returnapply;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;
import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.action.ConfirmAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderSummary;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSlotExtractor;
import com.smartcs.smart_cs_agent.tools.ReturnToolData;
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
 * 独立的退货申请多轮 Workflow。
 *
 * <p>完整调用链：</p>
 * <pre>
 * Router 选择/恢复本 Workflow
 *   -> 收集订单号
 *   -> order_lookup 校验订单存在且属于当前 userId
 *   -> 收集原因
 *   -> ConfirmAction 等待明确确认
 *   -> return_apply
 *   -> FinalAnswerAction
 * </pre>
 *
 * <p>本类只返回 Action，不直接访问 Repository 或执行工具。订单归属通过只读 Capability 结果验证，
 * 退货写入仍由 CapabilityGateway 之后的领域能力负责。</p>
 */
@Component
public class ReturnApplyWorkflow implements AgentWorkflow {

    public static final String ID = ReturnApplyState.WORKFLOW_ID;
    public static final String VERSION = "2.0.0";

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "收集退货信息、校验订单归属、确认后提交退货申请",
            200
    );

    private final CustomerServiceSlotExtractor slotExtractor = new CustomerServiceSlotExtractor();
    private final GlobalInterruptPolicy interruptPolicy;
    private final ReturnApplyWorkflowProperties properties;

    public ReturnApplyWorkflow(GlobalInterruptPolicy interruptPolicy,
                               ReturnApplyWorkflowProperties properties) {
        this.interruptPolicy = interruptPolicy;
        this.properties = properties;
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        if (!properties.isEnabled()) {
            return WorkflowMatchResult.noMatch("return-apply-v2 功能开关已关闭");
        }

        String message = normalize(context.getMessage());
        if (containsAny(message, "退货进度", "退货状态", "退款进度", "ret-")) {
            return WorkflowMatchResult.noMatch("这是退货查询而不是新申请");
        }

        if (containsAny(message,
                "我要退货", "我想退货", "申请退货", "帮我退货",
                "我要退款", "申请退款", "我要换货", "申请换货",
                "我想退", "想退这个订单", "要退这个订单", "退这个订单", "不想要")) {
            return new WorkflowMatchResult(0.98, "命中明确退货/退款/换货申请表达");
        }

        if (message.contains("退货") || message.contains("退款") || message.contains("换货")) {
            // 模糊政策问题仍优先交给得分更高的 PolicyQaWorkflow。
            return new WorkflowMatchResult(0.65, "提到退换货，但没有明确申请动作");
        }
        return WorkflowMatchResult.noMatch("未命中退货申请表达");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        ReturnApplyState state = new ReturnApplyState(context.getState());

        // 全局打断必须先于槽位提取。否则“我要人工”可能被当成 return reason 保存。
        WorkflowDecision interrupted = handleInterrupt(context, state);
        if (interrupted != null) {
            return interrupted;
        }

        return switch (state.getStep()) {
            case START -> start(context, state);
            case WAITING_ORDER_ID -> collectOrderId(context, state);
            case VALIDATING_ORDER -> validateOrderOwnership(context, state);
            case WAITING_REASON -> collectReason(context, state);
            case WAITING_CONFIRMATION -> handleConfirmation(context, state);
            case INVOKING_RETURN_APPLY -> handleReturnApplyResult(context, state);
            case RETRYABLE_FAILED -> WorkflowDecision.next(
                    new FinalAnswerAction("退货系统刚才处理失败，请稍后重新发起，或要求转人工客服。"),
                    "技术失败后结束本次流程，避免自动重复提交"
            );
            case COMPLETED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次退货申请已经处理完成。"),
                    "Workflow 已完成"
            );
            case CANCELLED -> WorkflowDecision.next(
                    new CancelWorkflowAction("本次退货申请已取消，没有提交任何退货操作。"),
                    "Workflow 已取消"
            );
        };
    }

    private WorkflowDecision start(TurnContext context, ReturnApplyState state) {
        String orderId = slotExtractor.extractOrderId(context.getMessage());
        if (orderId == null) {
            state.transitionTo(ReturnApplyStep.WAITING_ORDER_ID);
            return WorkflowDecision.next(
                    new AskUserAction("好的，请提供需要退货的订单号，例如 ORD-003。"),
                    "退货申请缺少订单号"
            );
        }
        captureOrderAndPossibleReason(context, state, orderId);
        return validateOrderAction(orderId);
    }

    private WorkflowDecision collectOrderId(TurnContext context, ReturnApplyState state) {
        String orderId = slotExtractor.extractOrderId(context.getMessage());
        if (orderId == null) {
            return WorkflowDecision.next(
                    new AskUserAction("我还没有识别到订单号，请提供类似 ORD-003 的订单号。"),
                    "仍未取得合法订单号"
            );
        }
        captureOrderAndPossibleReason(context, state, orderId);
        return validateOrderAction(orderId);
    }

    private void captureOrderAndPossibleReason(TurnContext context,
                                               ReturnApplyState state,
                                               String orderId) {
        state.setOrderId(orderId);
        String reason = slotExtractor.extractReturnReason(context.getMessage(), orderId);
        if (slotExtractor.hasText(reason)) {
            state.setReason(reason);
        }
        state.transitionTo(ReturnApplyStep.VALIDATING_ORDER);
    }

    private WorkflowDecision validateOrderAction(String orderId) {
        return WorkflowDecision.next(
                new CapabilityAction(CapabilityIds.ORDER_LOOKUP, Map.of("order_id", orderId)),
                "提交前先查询订单并校验归属"
        );
    }

    private WorkflowDecision validateOrderOwnership(TurnContext context, ReturnApplyState state) {
        CapabilityObservation result = latestCapabilityResult(context);
        if (result == null || !CapabilityIds.ORDER_LOOKUP.equals(result.getCapabilityId())) {
            state.transitionTo(ReturnApplyStep.RETRYABLE_FAILED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("暂时无法验证订单信息，请稍后重试。"),
                    "没有获得预期的 order_lookup 结果，停止副作用流程"
            );
        }
        if (result.isBusinessRejected() && "ORDER_NOT_FOUND".equals(result.getErrorCode())) {
            state.setOrderId(null);
            state.transitionTo(ReturnApplyStep.WAITING_ORDER_ID);
            return WorkflowDecision.next(
                    new AskUserAction("没有找到该订单，请检查后提供正确的订单号。"),
                    "订单查询返回业务拒绝：ORDER_NOT_FOUND"
            );
        }
        if (!result.isSuccess() || !(result.getData() instanceof OrderSummary order)) {
            state.transitionTo(ReturnApplyStep.RETRYABLE_FAILED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("暂时无法验证订单信息，请稍后重试。"),
                    "订单查询技术失败或返回契约不正确"
            );
        }
        String requestUserId = context.getRequest().getUserId();
        if (requestUserId == null
                || requestUserId.isBlank()
                || "anonymous".equalsIgnoreCase(requestUserId)) {
            context.getState().markWorkflowCancelled();
            return WorkflowDecision.next(
                    new FinalAnswerAction("申请退货前需要登录并验证身份，本次没有提交退货操作。"),
                    "匿名用户不能执行退货副作用"
            );
        }
        if (!requestUserId.equals(order.getUserId())) {
            context.getState().markWorkflowCancelled();
            return WorkflowDecision.next(
                    new FinalAnswerAction("该订单不属于当前用户，无法为它提交退货申请。"),
                    "服务端订单归属校验失败"
            );
        }

        state.setProductName(order.getProductName());
        if (!slotExtractor.hasText(state.getReason())) {
            state.transitionTo(ReturnApplyStep.WAITING_REASON);
            return WorkflowDecision.next(
                    new AskUserAction("订单验证通过。请简单说明退货原因，例如质量问题、不想要了或尺码不合适。"),
                    "订单已验证，仍缺退货原因"
            );
        }
        state.transitionTo(ReturnApplyStep.WAITING_CONFIRMATION);
        return confirmationAction(state, "订单和原因已完整，等待用户确认");
    }

    private WorkflowDecision collectReason(TurnContext context, ReturnApplyState state) {
        String reason = slotExtractor.extractReturnReason(context.getMessage(), state.getOrderId());
        if (!slotExtractor.hasText(reason)) {
            return WorkflowDecision.next(
                    new AskUserAction("请简单说明退货原因，例如质量问题、不想要了或尺码不合适。"),
                    "仍未取得有效退货原因"
            );
        }

        state.setReason(reason);
        state.transitionTo(ReturnApplyStep.WAITING_CONFIRMATION);
        return confirmationAction(state, "已收集退货原因，执行前必须明确确认");
    }

    private WorkflowDecision handleConfirmation(TurnContext context, ReturnApplyState state) {
        String answer = normalize(context.getMessage());
        if (isAffirmative(answer)) {
            state.transitionTo(ReturnApplyStep.INVOKING_RETURN_APPLY);
            return WorkflowDecision.next(
                    new CapabilityAction(CapabilityIds.RETURN_APPLY, Map.of(
                            "order_id", state.getOrderId(),
                            "reason", state.getReason()
                    )),
                    "用户明确确认，允许执行退货申请"
            );
        }

        return confirmationAction(state, "用户回复不够明确，继续等待确认且不调用工具");
    }

    private WorkflowDecision confirmationAction(ReturnApplyState state, String reason) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderId", state.getOrderId());
        summary.put("productName", state.getProductName());
        summary.put("reason", state.getReason());

        String question = "请确认是否提交退货申请：\n"
                + "- 订单号：" + state.getOrderId() + "\n"
                + "- 商品：" + safeText(state.getProductName(), "未知商品") + "\n"
                + "- 原因：" + state.getReason() + "\n"
                + "回复“确认提交”后才会创建退货申请；回复“取消”则不会提交。";
        return WorkflowDecision.next(new ConfirmAction(question, CapabilityIds.RETURN_APPLY, summary), reason);
    }

    private WorkflowDecision handleReturnApplyResult(TurnContext context, ReturnApplyState state) {
        CapabilityObservation result = latestCapabilityResult(context);
        if (result == null || !CapabilityIds.RETURN_APPLY.equals(result.getCapabilityId())) {
            state.transitionTo(ReturnApplyStep.RETRYABLE_FAILED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("退货申请没有得到可靠处理结果，为避免重复提交，请稍后查询或联系人工。"),
                    "缺少预期结果，不自动重试有副作用操作"
            );
        }
        if (result.isBusinessRejected()) {
            state.transitionTo(ReturnApplyStep.COMPLETED);
            context.getState().markWorkflowCompleted();
            return WorkflowDecision.next(
                    new FinalAnswerAction(safeText(result.getMessage(), "退货申请未提交。")),
                    "退货领域能力返回业务拒绝：" + safeText(result.getErrorCode(), "UNKNOWN")
            );
        }
        if (!result.isSuccess()) {
            state.transitionTo(ReturnApplyStep.RETRYABLE_FAILED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("退货申请提交失败：" + safeText(result.getMessage(), "系统暂时不可用")),
                    "Capability 返回技术失败"
            );
        }

        ReturnToolData data = ReturnToolData.fromMap(result.getData());
        if (data != null) {
            state.transitionTo(ReturnApplyStep.COMPLETED);
            if (Boolean.TRUE.equals(data.getSubmitted())) {
                context.getState().markWorkflowCompleted();
                return WorkflowDecision.next(
                        new FinalAnswerAction("退货申请已提交成功：\n"
                                + "- 退货单号：" + data.getReturnId() + "\n"
                                + "- 订单号：" + data.getOrderId() + "\n"
                                + "- 当前状态：" + data.getStatus()),
                        "退货领域能力确认已成功创建申请"
                );
            }

            // 重复申请、订单消失等是业务拒绝，不应伪装成系统异常，也不能自动重试。
            context.getState().markWorkflowCompleted();
            return WorkflowDecision.next(
                    new FinalAnswerAction(safeText(result.getMessage(), "退货申请未提交。")),
                    "退货领域能力返回业务拒绝：" + safeText(data.getReasonCode(), "UNKNOWN")
            );
        }

        state.transitionTo(ReturnApplyStep.RETRYABLE_FAILED);
        return WorkflowDecision.next(
                new FinalAnswerAction("退货系统返回了无法识别的结果，为避免重复提交，请联系人工客服。"),
                "return_apply 输出不符合结构化契约"
        );
    }

    private WorkflowDecision handleInterrupt(TurnContext context, ReturnApplyState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(context.getMessage());
        if (interrupt.getType() == GlobalInterruptType.CANCEL) {
            state.transitionTo(ReturnApplyStep.CANCELLED);
            return WorkflowDecision.next(
                    new CancelWorkflowAction("本次退货申请已取消，没有提交任何退货操作。"),
                    interrupt.getReason()
            );
        }
        if (interrupt.getType() == GlobalInterruptType.HANDOFF) {
            Map<String, Object> handoffContext = new LinkedHashMap<>(state.snapshot());
            state.transitionTo(ReturnApplyStep.CANCELLED);
            return WorkflowDecision.next(
                    new HandoffAction(
                            interrupt.getHandoffReasonCode(),
                            HandoffSummarySupport.build("退货申请", context, interrupt, handoffContext),
                            handoffContext
                    ),
                    interrupt.getReason()
            );
        }
        return null;
    }

    private CapabilityObservation latestCapabilityResult(TurnContext context) {
        Observation observation = context.getLatestObservationByType("capability_result");
        return observation != null && observation.getData() instanceof CapabilityObservation result ? result : null;
    }

    private boolean isAffirmative(String text) {
        return text.equals("是")
                || text.equals("好的")
                || text.equals("确认")
                || text.equals("确认提交")
                || text.equals("同意")
                || text.equals("提交")
                || text.equals("yes")
                || text.equals("ok");
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
