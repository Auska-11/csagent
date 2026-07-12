package com.smartcs.smart_cs_agent.workflow.core;

/** GlobalInterruptPolicy 的可解释判断结果。 */
public final class GlobalInterruptDecision {

    private final GlobalInterruptType type;
    private final String reason;
    private final String handoffReasonCode;

    public GlobalInterruptDecision(GlobalInterruptType type, String reason) {
        this(type, reason, "");
    }

    /**
     * 创建一条带稳定升级原因码的全局中断判断。
     *
     * <p>reason 是给开发者看的中文解释；handoffReasonCode 是会进入工单的稳定机器值，
     * 例如 COMPLAINT。把两者分开后，Workflow 不需要再各自复制一套投诉/赔偿分类规则。</p>
     */
    public GlobalInterruptDecision(GlobalInterruptType type,
                                   String reason,
                                   String handoffReasonCode) {
        this.type = type == null ? GlobalInterruptType.NONE : type;
        this.reason = reason == null ? "" : reason;
        this.handoffReasonCode = handoffReasonCode == null ? "" : handoffReasonCode;
    }

    public GlobalInterruptType getType() {
        return type;
    }

    public String getReason() {
        return reason;
    }

    /**
     * 返回进入人工工单的稳定原因码。
     *
     * <p>只有 HANDOFF 判断会携带该值；取消、切换任务和普通消息返回空字符串。</p>
     */
    public String getHandoffReasonCode() {
        return handoffReasonCode;
    }
}
