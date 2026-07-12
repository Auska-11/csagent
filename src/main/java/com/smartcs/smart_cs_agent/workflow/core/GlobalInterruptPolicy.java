package com.smartcs.smart_cs_agent.workflow.core;

import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 在 Workflow 把用户回复当成普通槽位之前，先识别取消、转人工和明确切换任务。
 *
 * <p>例如退货流程正在等待“原因”，用户回复“我要人工”时，绝不能把“我要人工”保存成退货原因。
 * 第一版使用确定性关键词，行为可测试；以后可以增加分类器，但接口保持不变。</p>
 */
@Component
public class GlobalInterruptPolicy {

    public static final String REASON_USER_REQUESTED_HUMAN = "USER_REQUESTED_HUMAN";
    public static final String REASON_COMPLAINT = "COMPLAINT";
    public static final String REASON_COMPENSATION_OR_DISPUTE = "COMPENSATION_OR_DISPUTE";

    public GlobalInterruptDecision evaluate(String message) {
        String text = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);

        // “什么情况可以转人工”是在问规则，不是在授权系统创建工单。
        // 这条负向边界必须先于人工关键词判断，否则包含“人工”两个字就会产生真实副作用。
        if (!isHandoffPolicyQuestion(text)) {
            String handoffReasonCode = classifyHandoffReason(text);
            if (!handoffReasonCode.isBlank()) {
                return new GlobalInterruptDecision(
                        GlobalInterruptType.HANDOFF,
                        explainHandoffReason(handoffReasonCode),
                        handoffReasonCode
                );
            }
        }
        if (containsAny(text, "取消", "算了", "不用了", "不退了", "先不退")) {
            return new GlobalInterruptDecision(GlobalInterruptType.CANCEL, "用户明确取消当前任务");
        }
        if (containsAny(text, "换个问题", "切换任务", "改查", "先查商品", "先查订单")) {
            return new GlobalInterruptDecision(GlobalInterruptType.SWITCH_TASK, "用户明确切换到另一个任务");
        }
        return new GlobalInterruptDecision(GlobalInterruptType.NONE, "未命中全局打断表达");
    }

    /**
     * 判断用户是在询问人工转接规则，而不是要求立即转接。
     *
     * <p>PolicyQaWorkflow 与 EscalationWorkflow 共用这个方法，避免两个 Workflow 对同一句话
     * 给出相反判断。以后补充新的规则问法，也只需要修改这一处。</p>
     */
    public boolean isHandoffPolicyQuestion(String message) {
        String text = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        // 一句话可能先提到规则，后半句又明确要求执行，例如“我了解投诉规则，现在请转人工”。
        // 明确动作应优先，不能因为前半句出现“规则”就吞掉真实的升级请求。
        if (containsAny(
                text,
                "我要人工", "找人工", "帮我转人工", "现在转人工", "立即转人工",
                "我要投诉", "投诉你们", "我要赔偿", "要求赔偿", "我要维权",
                "去12315", "找律师", "起诉你们"
        )) {
            return false;
        }
        return containsAny(
                text,
                "转人工规则", "人工转接规则", "什么情况转人工", "什么情况可以转人工",
                "什么时候转人工", "什么时候可以转人工", "哪些情况需要人工", "人工客服政策",
                "投诉规则", "赔偿规则"
        );
    }

    /**
     * 将自然语言升级诉求转换为稳定原因码。
     *
     * <p>优先级很重要：一句“我要人工处理赔偿”同时包含人工和赔偿，工单应归类为纠纷，
     * 而不是普通人工请求。原因码越具体，后续工单系统越容易正确设置优先级。</p>
     */
    private String classifyHandoffReason(String text) {
        if (containsAny(text, "赔偿", "补偿", "维权", "律师", "起诉", "12315", "纠纷")) {
            return REASON_COMPENSATION_OR_DISPUTE;
        }
        if (containsAny(
                text,
                "我要投诉", "投诉你们", "投诉客服", "强烈投诉", "差评", "太差", "不满意",
                "生气", "垃圾", "客服经理"
        )) {
            return REASON_COMPLAINT;
        }
        if (containsAny(
                text,
                "我要人工", "找人工", "转人工", "人工客服", "真人客服", "找真人",
                "转客服", "human agent"
        )) {
            return REASON_USER_REQUESTED_HUMAN;
        }
        return "";
    }

    private String explainHandoffReason(String reasonCode) {
        return switch (reasonCode) {
            case REASON_COMPENSATION_OR_DISPUTE -> "用户提出赔偿、维权或法律纠纷";
            case REASON_COMPLAINT -> "用户提出投诉或强烈负面反馈";
            case REASON_USER_REQUESTED_HUMAN -> "用户明确要求人工客服";
            default -> "用户问题需要人工复核";
        };
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
