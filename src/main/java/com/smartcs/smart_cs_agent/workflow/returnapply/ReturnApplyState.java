package com.smartcs.smart_cs_agent.workflow.returnapply;

import com.smartcs.smart_cs_agent.agent.context.ConversationState;

import java.util.Map;
import java.util.Objects;

/**
 * return_apply Workflow 对其命名空间状态的类型安全访问器。
 *
 * <p>底层仍由 ConversationState 保存，保证能跨 HTTP 请求恢复；业务代码不再到处写字符串 key，
 * 从而减少把 return_reason 拼错或误读其他 Workflow 状态的风险。</p>
 */
public final class ReturnApplyState {

    public static final String WORKFLOW_ID = "return_apply";

    private static final String KEY_STEP = "step";
    private static final String KEY_ORDER_ID = "orderId";
    private static final String KEY_REASON = "reason";
    private static final String KEY_PRODUCT_NAME = "productName";

    private final ConversationState conversationState;

    public ReturnApplyState(ConversationState conversationState) {
        this.conversationState = Objects.requireNonNull(conversationState, "conversationState cannot be null");
    }

    public ReturnApplyStep getStep() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_STEP);
        if (value == null) {
            return ReturnApplyStep.START;
        }
        try {
            return ReturnApplyStep.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            // 状态值可能来自旧版本或损坏数据。明确失败比从错误步骤继续执行副作用更安全。
            throw new IllegalStateException("Unknown return apply step: " + value, e);
        }
    }

    public void transitionTo(ReturnApplyStep step) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_STEP, step.name());
    }

    public String getOrderId() {
        return textState(KEY_ORDER_ID);
    }

    public void setOrderId(String orderId) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_ORDER_ID, orderId);
    }

    public String getReason() {
        return textState(KEY_REASON);
    }

    public void setReason(String reason) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_REASON, reason);
    }

    public String getProductName() {
        return textState(KEY_PRODUCT_NAME);
    }

    public void setProductName(String productName) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_PRODUCT_NAME, productName);
    }

    public Map<String, Object> snapshot() {
        return conversationState.getWorkflowStateSnapshot(WORKFLOW_ID);
    }

    private String textState(String key) {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, key);
        return value == null ? null : value.toString();
    }
}
