package com.smartcs.smart_cs_agent.workflow.userprofile;

import com.smartcs.smart_cs_agent.agent.context.ConversationState;

import java.util.Map;
import java.util.Objects;

/** 在 user_profile 命名空间保存偏好和当前步骤。 */
public final class UserProfileState {

    public static final String WORKFLOW_ID = "user_profile";

    private static final String KEY_STEP = "step";
    private static final String KEY_PREFERENCE = "preference";

    private final ConversationState conversationState;

    public UserProfileState(ConversationState conversationState) {
        this.conversationState = Objects.requireNonNull(
                conversationState,
                "conversationState cannot be null"
        );
    }

    public UserProfileStep getStep() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_STEP);
        if (value == null) {
            return UserProfileStep.START;
        }
        try {
            return UserProfileStep.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown user profile step: " + value, e);
        }
    }

    public void transitionTo(UserProfileStep step) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_STEP, step.name());
    }

    public String getPreference() {
        Object value = conversationState.getWorkflowState(WORKFLOW_ID, KEY_PREFERENCE);
        return value == null ? null : value.toString();
    }

    public void setPreference(String preference) {
        conversationState.setWorkflowState(WORKFLOW_ID, KEY_PREFERENCE, preference);
    }

    public Map<String, Object> snapshot() {
        return conversationState.getWorkflowStateSnapshot(WORKFLOW_ID);
    }
}
