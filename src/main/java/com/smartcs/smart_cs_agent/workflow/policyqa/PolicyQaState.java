package com.smartcs.smart_cs_agent.workflow.policyqa;

import com.smartcs.smart_cs_agent.agent.context.ConversationState;

import java.util.Map;
import java.util.Objects;

/** 在 policy_qa 命名空间保存政策问题和当前步骤。 */
public final class PolicyQaState {

    public static final String WORKFLOW_ID = "policy_qa";

    private static final String KEY_STEP = "step";
    private static final String KEY_QUERY = "query";

    private final ConversationState conversationState;

    public PolicyQaState(ConversationState conversationState) {
        this.conversationState = Objects.requireNonNull(
                conversationState,
                "conversationState cannot be null"
        );
    }

    public PolicyQaStep getStep() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_STEP);
        if (value == null) {
            return PolicyQaStep.START;
        }
        try {
            return PolicyQaStep.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown policy QA step: " + value, e);
        }
    }

    public void transitionTo(PolicyQaStep step) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_STEP, step.name());
    }

    public String getQuery() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_QUERY);
        return value == null ? null : value.toString();
    }

    public void setQuery(String query) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_QUERY, query);
    }

    public Map<String, Object> snapshot() {
        return conversationState.getWorkflowStateSnapshot(WORKFLOW_ID);
    }
}
