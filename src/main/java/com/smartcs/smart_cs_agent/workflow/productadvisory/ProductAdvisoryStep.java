package com.smartcs.smart_cs_agent.workflow.productadvisory;

/** 商品咨询 Workflow 的内部步骤。 */
public enum ProductAdvisoryStep {
    START,
    WAITING_KEYWORD,
    SEARCHING,
    COMPLETED,
    CANCELLED,
    FAILED
}
