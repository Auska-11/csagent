package com.smartcs.smart_cs_agent.workflow.orderquery;

import com.smartcs.smart_cs_agent.agent.context.ConversationState;

import java.util.Map;
import java.util.Objects;

/**
 * OrderQueryWorkflow 对私有会话状态的类型安全访问器。
 *
 * <p>所有 key 都封装在这里，Workflow 不需要到处写 {@code "orderId"}。状态仍保存在
 * ConversationState 的 order_query 命名空间中，因此能跨 HTTP 请求恢复，也不会污染退货流程。</p>
 */
public final class OrderQueryState {

    public static final String WORKFLOW_ID = "order_query";

    private static final String KEY_STEP = "step";
    private static final String KEY_ORDER_ID = "orderId";

    private final ConversationState conversationState;

    public OrderQueryState(ConversationState conversationState) {
        this.conversationState = Objects.requireNonNull(
                conversationState,
                "conversationState cannot be null"
        );
    }

    public OrderQueryStep getStep() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_STEP);
        if (value == null) {
            return OrderQueryStep.START;
        }
        try {
            return OrderQueryStep.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown order query step: " + value, e);
        }
    }

    public void transitionTo(OrderQueryStep step) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_STEP, step.name());
    }

    public String getOrderId() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_ORDER_ID);
        return value == null ? null : value.toString();
    }

    public void setOrderId(String orderId) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_ORDER_ID, orderId);
    }

    public Map<String, Object> snapshot() {
        return conversationState.getWorkflowStateSnapshot(WORKFLOW_ID);
    }
}
