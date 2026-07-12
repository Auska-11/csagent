package com.smartcs.smart_cs_agent.workflow.policyqa;

/** 通用政策问答 Workflow 的内部步骤。 */
public enum PolicyQaStep {
    START,
    WAITING_TOPIC,
    SEARCHING,
    COMPLETED,
    CANCELLED,
    FAILED
}
