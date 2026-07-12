package com.smartcs.smart_cs_agent.workflow.orderquery;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.Observation;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderListResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.OrderSummary;
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
 * 独立订单查询 Workflow。
 *
 * <p>职责：</p>
 * <ul>
 *     <li>识别单个订单查询或“我的全部订单”。</li>
 *     <li>缺订单号时跨轮等待用户补充。</li>
 *     <li>发出 order.lookup / order.list-by-user Action。</li>
 *     <li>读取稳定订单契约并生成确定性回复。</li>
 * </ul>
 *
 * <p>不负责：不访问 Repository、不决定本地还是 MCP Provider、不信任用户参数中的 userId。</p>
 *
 * <p>典型调用链：</p>
 * <pre>
 * “帮我查订单” -> WAITING_ORDER_ID -> “ORD-001”
 *   -> order.lookup -> OrderSummary -> 最终回复
 * </pre>
 */
@Component
public class OrderQueryWorkflow implements AgentWorkflow {

    public static final String ID = OrderQueryState.WORKFLOW_ID;
    public static final String VERSION = "1.0.0";

    private final WorkflowDescriptor descriptor = new WorkflowDescriptor(
            ID,
            VERSION,
            "查询单个订单状态、物流，或当前用户的全部订单",
            170
    );

    private final CustomerServiceSlotExtractor slotExtractor = new CustomerServiceSlotExtractor();
    private final GlobalInterruptPolicy interruptPolicy;

    public OrderQueryWorkflow(GlobalInterruptPolicy interruptPolicy) {
        this.interruptPolicy = interruptPolicy;
    }

    @Override
    public WorkflowDescriptor descriptor() {
        return descriptor;
    }

    /**
     * 匹配阶段必须无副作用。退货申请和政策问答主动让路，避免“我要退这个订单”被查询流程抢走。
     */
    @Override
    public WorkflowMatchResult match(RoutingContext context) {
        String message = normalize(context.getMessage());
        if (containsAny(message, "退货", "退款", "换货", "退换", "申请退")) {
            return WorkflowMatchResult.noMatch("退换货任务不属于订单查询");
        }
        if (containsAny(message, "退货政策", "保修", "质保", "发票", "配送政策", "七天无理由")) {
            return WorkflowMatchResult.noMatch("政策问题应交给知识问答 Workflow");
        }
        if (isUserOrderListRequest(message)) {
            return new WorkflowMatchResult(0.99, "命中当前用户订单列表表达");
        }
        if (slotExtractor.extractOrderId(message) != null) {
            return new WorkflowMatchResult(0.97, "消息中包含合法订单号");
        }
        if (containsAny(message, "订单", "物流", "快递", "发货", "到哪", "tracking", "order")) {
            return new WorkflowMatchResult(0.93, "命中订单状态或物流表达");
        }
        return WorkflowMatchResult.noMatch("未命中订单查询表达");
    }

    @Override
    public WorkflowDecision nextAction(TurnContext context) {
        context.getState().setActiveIntent("order_query");
        OrderQueryState state = new OrderQueryState(context.getState());

        WorkflowDecision interrupted = handleInterrupt(context, state);
        if (interrupted != null) {
            return interrupted;
        }

        // 订单和物流属于用户私有信息。只拥有一个 userId 字符串还不够，必须经过服务端认证。
        if (!context.getRequest().getPrincipal().isAuthenticated()) {
            state.transitionTo(OrderQueryStep.CANCELLED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("查询订单前需要先登录并验证身份。请使用有效账号重新发起查询。"),
                    "订单查询拒绝未认证身份"
            );
        }

        return switch (state.getStep()) {
            case START -> start(context, state);
            case WAITING_ORDER_ID -> collectOrderId(context, state);
            case LOOKING_UP_ORDER -> handleOrderLookupResult(context, state);
            case LISTING_USER_ORDERS -> handleOrderListResult(context, state);
            case COMPLETED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次订单查询已经完成。"),
                    "订单查询 Workflow 已完成"
            );
            case CANCELLED -> WorkflowDecision.next(
                    new FinalAnswerAction("本次订单查询已取消。"),
                    "订单查询 Workflow 已取消"
            );
            case FAILED -> WorkflowDecision.next(
                    new FinalAnswerAction("订单查询暂时失败，请稍后重试或联系人工客服。"),
                    "订单查询 Workflow 已失败"
            );
        };
    }

    private WorkflowDecision start(TurnContext context, OrderQueryState state) {
        if (isUserOrderListRequest(normalize(context.getMessage()))) {
            state.transitionTo(OrderQueryStep.LISTING_USER_ORDERS);
            return WorkflowDecision.next(
                    new CapabilityAction(CapabilityIds.ORDER_LIST_BY_USER, Map.of()),
                    "查询服务端认证用户的全部订单"
            );
        }

        String orderId = slotExtractor.extractOrderId(context.getMessage());
        if (orderId == null) {
            state.transitionTo(OrderQueryStep.WAITING_ORDER_ID);
            return WorkflowDecision.next(
                    new AskUserAction("请提供需要查询的订单号，例如 ORD-001。"),
                    "订单查询缺少订单号"
            );
        }
        return lookupOrder(state, orderId);
    }

    private WorkflowDecision collectOrderId(TurnContext context, OrderQueryState state) {
        String orderId = slotExtractor.extractOrderId(context.getMessage());
        if (orderId == null) {
            return WorkflowDecision.next(
                    new AskUserAction("我还没有识别到订单号，请提供类似 ORD-001 的编号。"),
                    "仍未取得合法订单号"
            );
        }
        return lookupOrder(state, orderId);
    }

    private WorkflowDecision lookupOrder(OrderQueryState state, String orderId) {
        state.setOrderId(orderId);
        state.transitionTo(OrderQueryStep.LOOKING_UP_ORDER);
        return WorkflowDecision.next(
                new CapabilityAction(CapabilityIds.ORDER_LOOKUP, Map.of("order_id", orderId)),
                "调用订单查询能力；归属校验由 Provider 使用 Principal 完成"
        );
    }

    private WorkflowDecision handleOrderLookupResult(TurnContext context, OrderQueryState state) {
        CapabilityObservation result = latestCapabilityResult(context);
        if (result == null || !CapabilityIds.ORDER_LOOKUP.equals(result.getCapabilityId())) {
            state.transitionTo(OrderQueryStep.FAILED);
            return technicalFailure("没有获得预期的 order.lookup 结果");
        }
        if (result.isBusinessRejected()) {
            if ("ORDER_NOT_FOUND".equals(result.getErrorCode())) {
                state.setOrderId(null);
                state.transitionTo(OrderQueryStep.WAITING_ORDER_ID);
                return WorkflowDecision.next(
                        new AskUserAction("没有找到该订单，请检查订单号后重新提供。"),
                        "订单不存在，继续等待正确订单号"
                );
            }
            state.transitionTo(OrderQueryStep.COMPLETED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("该订单不属于当前登录用户，无法查看订单详情。"),
                    "订单归属校验未通过"
            );
        }
        if (!result.isSuccess() || !(result.getData() instanceof OrderSummary order)) {
            state.transitionTo(OrderQueryStep.FAILED);
            return technicalFailure("订单能力技术失败或输出契约不正确");
        }

        state.transitionTo(OrderQueryStep.COMPLETED);
        return WorkflowDecision.next(
                new FinalAnswerAction(formatOrder(order)),
                "订单查询成功，使用稳定 OrderSummary 组织回复"
        );
    }

    private WorkflowDecision handleOrderListResult(TurnContext context, OrderQueryState state) {
        CapabilityObservation result = latestCapabilityResult(context);
        if (result == null || !CapabilityIds.ORDER_LIST_BY_USER.equals(result.getCapabilityId())) {
            state.transitionTo(OrderQueryStep.FAILED);
            return technicalFailure("没有获得预期的 order.list-by-user 结果");
        }
        if (!result.isSuccess() || !(result.getData() instanceof OrderListResult orders)) {
            state.transitionTo(OrderQueryStep.FAILED);
            return technicalFailure("订单列表能力失败或输出契约不正确");
        }

        state.transitionTo(OrderQueryStep.COMPLETED);
        if (orders.isEmpty()) {
            return WorkflowDecision.next(
                    new FinalAnswerAction("当前登录账号下还没有订单。"),
                    "当前用户订单列表为空"
            );
        }

        StringBuilder answer = new StringBuilder("您当前共有 ")
                .append(orders.getCount())
                .append(" 个订单：\n");
        for (int i = 0; i < orders.getOrders().size(); i++) {
            OrderSummary order = orders.getOrders().get(i);
            answer.append(i + 1)
                    .append(". ")
                    .append(order.getOrderId())
                    .append(" | ")
                    .append(safeText(order.getProductName(), "未知商品"))
                    .append(" | ")
                    .append(safeText(order.getStatus(), "状态未知"));
            if (order.hasTrackingNo()) {
                answer.append(" | 物流：").append(order.getTrackingNo());
            }
            if (i < orders.getOrders().size() - 1) {
                answer.append("\n");
            }
        }
        return WorkflowDecision.next(
                new FinalAnswerAction(answer.toString()),
                "订单列表查询成功"
        );
    }

    private WorkflowDecision technicalFailure(String reason) {
        return WorkflowDecision.next(
                new FinalAnswerAction("订单系统暂时无法完成查询，请稍后重试或联系人工客服。"),
                reason
        );
    }

    private WorkflowDecision handleInterrupt(TurnContext context, OrderQueryState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(context.getMessage());
        if (interrupt.getType() == GlobalInterruptType.CANCEL) {
            state.transitionTo(OrderQueryStep.CANCELLED);
            return WorkflowDecision.next(
                    new FinalAnswerAction("本次订单查询已取消。"),
                    interrupt.getReason()
            );
        }
        if (interrupt.getType() == GlobalInterruptType.HANDOFF) {
            Map<String, Object> handoffContext = new LinkedHashMap<>(state.snapshot());
            state.transitionTo(OrderQueryStep.CANCELLED);
            return WorkflowDecision.next(
                    new HandoffAction(
                            "USER_REQUESTED_HUMAN",
                            "用户在订单查询过程中要求人工客服",
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

    private String formatOrder(OrderSummary order) {
        StringBuilder answer = new StringBuilder("订单 ")
                .append(order.getOrderId())
                .append(" 信息：\n")
                .append("- 商品：")
                .append(safeText(order.getProductName(), "未知商品"))
                .append("\n- 状态：")
                .append(safeText(order.getStatus(), "状态未知"));
        if (order.hasTrackingNo()) {
            answer.append("\n- 物流单号：").append(order.getTrackingNo());
        } else {
            answer.append("\n- 物流：暂未生成物流单号");
        }
        return answer.toString();
    }

    private boolean isUserOrderListRequest(String message) {
        return containsAny(
                message,
                "我的订单", "全部订单", "所有订单", "有哪些订单", "订单列表", "查看我的订单",
                "my orders", "all orders"
        );
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
