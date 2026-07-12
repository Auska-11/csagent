package com.smartcs.smart_cs_agent.agent.handler.api;

/** Action 执行后，Runtime 应该如何控制循环。 */
public enum ActionFlow {
    /** Action 已产生 Observation，继续让 Workflow 决定下一步。 */
    CONTINUE,

    /** 已向用户提问或请求确认，暂停到下一轮。 */
    WAITING_USER,

    /** 当前 Workflow 正常结束。 */
    COMPLETED,

    /** 用户主动取消当前 Workflow；这是正常终态，但不能计入成功完成。 */
    CANCELLED,

    /** 已成功转交人工，当前 Workflow 结束。 */
    HANDED_OFF
}
