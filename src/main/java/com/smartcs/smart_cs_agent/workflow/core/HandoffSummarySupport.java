package com.smartcs.smart_cs_agent.workflow.core;

import com.smartcs.smart_cs_agent.agent.context.TurnContext;

import java.util.Map;

/**
 * 为“活跃 Workflow 被人工升级打断”生成统一工单摘要。
 *
 * <p>为什么不只把 stateSnapshot 放进 HandoffAction.context：Action context 主要用于 Trace，
 * HandoffTools 当前只接收 reason 和 summary。如果不把关键线索写入 summary，真实工单会丢失用户
 * 当前原话、订单号或已经收集的退货原因。</p>
 *
 * <p>本类不创建工单，也不判断是否应该转人工；它只负责把已确定的信息转换成有限长度的文本。</p>
 */
public final class HandoffSummarySupport {

    private static final int MESSAGE_MAX_LENGTH = 500;
    private static final int SUMMARY_MAX_LENGTH = 1_200;

    private HandoffSummarySupport() {
    }

    public static String build(String workflowLabel,
                               TurnContext context,
                               GlobalInterruptDecision interrupt,
                               Map<String, Object> workflowState) {
        StringBuilder summary = new StringBuilder();
        summary.append("用户在").append(safeLabel(workflowLabel)).append("过程中触发人工升级");
        summary.append("。升级原因：").append(interrupt.getReason());
        summary.append("。用户当前消息：")
                .append(limit(compact(context.getMessage()), MESSAGE_MAX_LENGTH));

        if (workflowState != null && !workflowState.isEmpty()) {
            summary.append("。已收集的流程信息：");
            boolean first = true;
            for (Map.Entry<String, Object> entry : workflowState.entrySet()) {
                Object value = entry.getValue();
                if (value == null || value.toString().isBlank()) {
                    continue;
                }
                if (!first) {
                    summary.append("，");
                }
                summary.append(entry.getKey()).append("=").append(compact(value.toString()));
                first = false;
            }
        }
        return limit(summary.toString(), SUMMARY_MAX_LENGTH);
    }

    private static String safeLabel(String value) {
        return value == null || value.isBlank() ? "当前业务" : value.trim();
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength);
    }
}
