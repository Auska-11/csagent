package com.smartcs.smart_cs_agent.workflow.productadvisory;

import com.smartcs.smart_cs_agent.agent.context.ConversationState;

import java.util.Map;
import java.util.Objects;

/** 在 product_advisory 命名空间保存搜索词、偏好来源和当前步骤。 */
public final class ProductAdvisoryState {

    public static final String WORKFLOW_ID = "product_advisory";

    private static final String KEY_STEP = "step";
    private static final String KEY_KEYWORD = "keyword";
    private static final String KEY_USED_PREFERENCE = "usedPreference";

    private final ConversationState conversationState;

    public ProductAdvisoryState(ConversationState conversationState) {
        this.conversationState = Objects.requireNonNull(
                conversationState,
                "conversationState cannot be null"
        );
    }

    public ProductAdvisoryStep getStep() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_STEP);
        if (value == null) {
            return ProductAdvisoryStep.START;
        }
        try {
            return ProductAdvisoryStep.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown product advisory step: " + value, e);
        }
    }

    public void transitionTo(ProductAdvisoryStep step) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_STEP, step.name());
    }

    public String getKeyword() {
        return text(KEY_KEYWORD);
    }

    public void setKeyword(String keyword) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_KEYWORD, keyword);
    }

    public String getUsedPreference() {
        return text(KEY_USED_PREFERENCE);
    }

    public void setUsedPreference(String preference) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_USED_PREFERENCE, preference);
    }

    public Map<String, Object> snapshot() {
        return conversationState.getWorkflowStateSnapshot(WORKFLOW_ID);
    }

    private String text(String key) {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, key);
        return value == null ? null : value.toString();
    }
}
