package com.smartcs.smart_cs_agent.workflow.core;

/** 用户对正在进行的多轮任务发出的全局打断类型。 */
public enum GlobalInterruptType {
    NONE,
    CANCEL,
    HANDOFF,
    SWITCH_TASK
}
