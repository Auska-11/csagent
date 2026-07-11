package com.smartcs.smart_cs_agent.workflow.core;

/** GlobalInterruptPolicy 的可解释判断结果。 */
public final class GlobalInterruptDecision {

    private final GlobalInterruptType type;
    private final String reason;

    public GlobalInterruptDecision(GlobalInterruptType type, String reason) {
        this.type = type == null ? GlobalInterruptType.NONE : type;
        this.reason = reason == null ? "" : reason;
    }

    public GlobalInterruptType getType() {
        return type;
    }

    public String getReason() {
        return reason;
    }
}
