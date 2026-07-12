package com.smartcs.smart_cs_agent.workflow.userprofile;

/** 用户资料 Workflow 的内部步骤。 */
public enum UserProfileStep {
    START,
    WAITING_PREFERENCE,
    LOOKING_UP_PROFILE,
    COMPLETED,
    CANCELLED,
    FAILED
}
