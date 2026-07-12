package com.smartcs.smart_cs_agent.workflow.returnquery;

/** 退货进度查询 Workflow 的内部步骤。 */
public enum ReturnQueryStep {
    START,
    WAITING_RETURN_ID,
    LOOKING_UP_RETURN,
    COMPLETED,
    CANCELLED,
    FAILED
}
