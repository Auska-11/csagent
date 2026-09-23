package com.smartcs.smart_cs_agent.handoff.sla;

/** 回调确认处置结果；三个值都是 SLA 侧认可的成功确认。 */
public enum SlaResultDisposition {
    APPLIED,
    DUPLICATE,
    SUPERSEDED
}
