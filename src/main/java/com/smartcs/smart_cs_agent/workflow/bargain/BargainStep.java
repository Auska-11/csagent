package com.smartcs.smart_cs_agent.workflow.bargain;

/** 议价 Workflow 的内部步骤。 */
public enum BargainStep {
    START,
    /** 已报出价格/答复，正等待买家下一轮回应（Workflow 处于 WAITING_USER 粘性状态）。 */
    WAITING_BUYER,
    COMPLETED,
    CANCELLED
}
