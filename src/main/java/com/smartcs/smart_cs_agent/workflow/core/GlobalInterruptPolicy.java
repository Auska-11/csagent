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

    public GlobalInterruptDecision evaluate(String message) {
        String text = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);

        if (containsAny(text, "人工", "真人客服", "转客服", "客服经理")) {
            return new GlobalInterruptDecision(GlobalInterruptType.HANDOFF, "用户明确要求人工客服");
        }
        if (containsAny(text, "取消", "算了", "不用了", "不退了", "先不退")) {
            return new GlobalInterruptDecision(GlobalInterruptType.CANCEL, "用户明确取消当前任务");
        }
        if (containsAny(text, "换个问题", "切换任务", "改查", "先查商品", "先查订单")) {
            return new GlobalInterruptDecision(GlobalInterruptType.SWITCH_TASK, "用户明确切换到另一个任务");
        }
        return new GlobalInterruptDecision(GlobalInterruptType.NONE, "未命中全局打断表达");
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
