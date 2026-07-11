package com.smartcs.smart_cs_agent.workflow.returnapply;

/** 退货申请 Workflow 内部状态机步骤。 */
public enum ReturnApplyStep {
    START,
    WAITING_ORDER_ID,
    VALIDATING_ORDER,
    WAITING_REASON,
    WAITING_CONFIRMATION,
    INVOKING_RETURN_APPLY,
    COMPLETED,
    CANCELLED,
    RETRYABLE_FAILED
}
