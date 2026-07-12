package com.smartcs.smart_cs_agent.workflow.returnquery;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.contracts.ReturnStatusSummary;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSlotExtractor;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 独立退货进度查询 Workflow。
 *
 * <p>它只处理“已经有退货单，查询进度”的场景；新建退货申请由 ReturnApplyWorkflow 负责。
 * 缺少 RET-001 形式的退货单号时，本 Workflow 会保持 WAITING_RETURN_ID 并跨轮恢复。</p>
 *
 * <p>权限校验由 LocalReturnLookupCapabilityProvider 完成：退货记录会关联订单，再比较订单所属用户
 * 与 ExecutionContext.principal。Workflow 不直接访问任何 Repository。</p>
 */
@Component
public class ReturnQueryWorkflow implements AgentWorkflow {

    public static final String ID = ReturnQueryState.WORKFLOW_ID;
    public static final String VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "查询当前用户自己的退货申请进度",
            175
    );

    private final CustomerServiceSlotExtractor slotExtractor = new CustomerServiceSlotExtractor();
    private final GlobalInterruptPolicy interruptPolicy;

    public ReturnQueryWorkflow(GlobalInterruptPolicy interruptPolicy) {
        this.interruptPolicy = interruptPolicy;
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        String message = normalize(context.getMessage());
        if (containsAny(message, "我要退货", "申请退货", "我要退款", "申请退款", "我要换货")) {
            return WorkflowMatchResult.noMatch("这是新建退货申请，不是进度查询");
        }
        if (slotExtractor.extractReturnId(message) != null) {
            return new WorkflowMatchResult(0.99, "消息中包含合法退货单号");
        }
        if (containsAny(
                message,
                "退货进度", "退货状态", "退换货进度", "退换货状态", "退款进度", "退款状态",
                "处理到哪", "审核进度", "return status", "return progress"
        )) {
            return new WorkflowMatchResult(0.97, "命中退货进度或状态表达");
        }
        return WorkflowMatchResult.noMatch("未命中退货进度查询表达");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        context.getState().setActiveIntent("return_query");
        ReturnQueryState state = new ReturnQueryState(context.getState());

        WorkflowDecision interrupted = handleInterrupt(context, state);
        if (interrupted != null) {
            return interrupted;
        }

        if (!context.getRequest().getPrincipal().isAuthenticated()) {
            state.transitionTo(ReturnQueryStep.CANCELLED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("查询退货进度前需要先登录并验证身份。"),
                    "退货查询拒绝未认证身份"
            );
        }

        return switch (state.getStep()) {
            case START -> start(context, state);
            case WAITING_RETURN_ID -> collectReturnId(context, state);
            case LOOKING_UP_RETURN -> handleLookupResult(context, state);
            case COMPLETED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次退货进度查询已经完成。"),
                    "退货查询 Workflow 已完成"
            );
            case CANCELLED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次退货进度查询已取消。"),
                    "退货查询 Workflow 已取消"
            );
            case FAILED -> technicalFailure("退货查询 Workflow 已失败");
        };
    }

    private WorkflowDecision start(TurnContext context, ReturnQueryState state) {
        String returnId = slotExtractor.extractReturnId(context.getMessage());
        if (returnId == null) {
            state.transitionTo(ReturnQueryStep.WAITING_RETURN_ID);
            return WorkflowDecision.next(
                    new AskUserAction("请提供退货单号，例如 RET-001，我来帮您查询处理进度。"),
                    "退货进度查询缺少退货单号"
            );
        }
        return lookup(state, returnId);
    }

    private WorkflowDecision collectReturnId(TurnContext context, ReturnQueryState state) {
        String returnId = slotExtractor.extractReturnId(context.getMessage());
        if (returnId == null) {
            return WorkflowDecision.next(
                    new AskUserAction("我还没有识别到退货单号，请提供类似 RET-001 的编号。"),
                    "仍未取得合法退货单号"
            );
        }
        return lookup(state, returnId);
    }

    private WorkflowDecision lookup(ReturnQueryState state, String returnId) {
        state.setReturnId(returnId);
        state.transitionTo(ReturnQueryStep.LOOKING_UP_RETURN);
        return WorkflowDecision.next(
                new CapabilityAction(CapabilityIds.RETURN_LOOKUP, Map.of("return_id", returnId)),
                "查询退货状态；归属校验由 Provider 关联订单完成"
        );
    }

    private WorkflowDecision handleLookupResult(TurnContext context, ReturnQueryState state) {
        CapabilityObservation result = latestCapabilityResult(context);
        if (result == null || !CapabilityIds.RETURN_LOOKUP.equals(result.getCapabilityId())) {
            state.transitionTo(ReturnQueryStep.FAILED);
            return technicalFailure("没有获得预期的 return.lookup 结果");
        }
        if (result.isBusinessRejected()) {
            if ("RETURN_NOT_FOUND".equals(result.getErrorCode())) {
                state.setReturnId(null);
                state.transitionTo(ReturnQueryStep.WAITING_RETURN_ID);
                return WorkflowDecision.next(
                        new AskUserAction("没有找到该退货单，请检查编号后重新提供。"),
                        "退货单不存在，继续等待正确编号"
                );
            }
            state.transitionTo(ReturnQueryStep.COMPLETED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("该退货单不属于当前登录用户，无法查看处理详情。"),
                    "退货单归属校验未通过"
            );
        }
        if (!result.isSuccess() || !(result.getData() instanceof ReturnStatusSummary status)) {
            state.transitionTo(ReturnQueryStep.FAILED);
            return technicalFailure("退货查询技术失败或输出契约不正确");
        }

        state.transitionTo(ReturnQueryStep.COMPLETED);
        String answer = "退货申请 " + status.getReturnId() + " 信息：\n"
                + "- 订单号：" + safeText(status.getOrderId(), "未知") + "\n"
                + "- 商品：" + safeText(status.getProductName(), "未知商品") + "\n"
                + "- 原因：" + safeText(status.getReason(), "未记录") + "\n"
                + "- 当前状态：" + safeText(status.getStatus(), "状态未知");
        return WorkflowDecision.next(
                new FinalAnswerAction(answer),
                "退货状态查询成功，使用稳定 ReturnStatusSummary 组织回复"
        );
    }

    private WorkflowDecision technicalFailure(String reason) {
        return WorkflowDecision.next(
                new FinalAnswerAction("退货系统暂时无法完成进度查询，请稍后重试或联系人工客服。"),
                reason
        );
    }

    private WorkflowDecision handleInterrupt(TurnContext context, ReturnQueryState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(context.getMessage());
        if (interrupt.getType() == GlobalInterruptType.CANCEL) {
            state.transitionTo(ReturnQueryStep.CANCELLED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("本次退货进度查询已取消。"),
                    interrupt.getReason()
            );
        }
        if (interrupt.getType() == GlobalInterruptType.HANDOFF) {
            Map<String, Object> handoffContext = new LinkedHashMap<>(state.snapshot());
            state.transitionTo(ReturnQueryStep.CANCELLED);
            return WorkflowDecision.next(
                    new HandoffAction(
                            "USER_REQUESTED_HUMAN",
                            "用户在退货进度查询过程中要求人工客服",
                            handoffContext
                    ),
                    interrupt.getReason()
            );
        }
        return null;
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
