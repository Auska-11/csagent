package com.smartcs.smart_cs_agent.workflow.returnquery;

import com.smartcs.smart_cs_agent.agent.context.ConversationState;

import java.util.Map;
import java.util.Objects;

/** 以 return_query 命名空间保存退货单号和当前步骤。 */
public final class ReturnQueryState {

    public static final String WORKFLOW_ID = "return_query";

    private static final String KEY_STEP = "step";
    private static final String KEY_RETURN_ID = "returnId";

    private final ConversationState conversationState;

    public ReturnQueryState(ConversationState conversationState) {
        this.conversationState = Objects.requireNonNull(
                conversationState,
                "conversationState cannot be null"
        );
    }

    public ReturnQueryStep getStep() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_STEP);
        if (value == null) {
            return ReturnQueryStep.START;
        }
        try {
            return ReturnQueryStep.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown return query step: " + value, e);
        }
    }

    public void transitionTo(ReturnQueryStep step) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_STEP, step.name());
    }

    public String getReturnId() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_RETURN_ID);
        return value == null ? null : value.toString();
    }

    public void setReturnId(String returnId) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_RETURN_ID, returnId);
    }

    public Map<String, Object> snapshot() {
        return conversationState.getWorkflowStateSnapshot(WORKFLOW_ID);
    }
}
