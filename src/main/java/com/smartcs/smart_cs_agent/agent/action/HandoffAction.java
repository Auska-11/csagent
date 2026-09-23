package com.smartcs.smart_cs_agent.agent.action;

import java.util.Collections;
import java.util.Map;

/**
 * 转人工 Action。
 *
 * 【为什么单独设计 HandoffAction？】
 * 转人工不是普通工具调用，也不是让 LLM 随便回复一句“我帮你转人工”。
 * 它表示 Agent 做出了一个明确决策：当前问题不适合继续自动处理，需要人工客服介入。
 *
 * 这样做的好处：
 * - Runtime 可以统一记录转人工 Trace
 * - 后续评测可以判断“是否应该转人工”
 * - 后续可以接真实工单系统或人工客服系统
 */
public class HandoffAction implements AgentAction {

    /**
     * 商品功能核实原因：买家询问的商品功能在知识里标记“未测试”，需要卖家实际核实。
     *
     * <p>sla-v1 模式下只有该原因会创建远端 PRODUCT_CHECK 核实单；普通转人工、投诉、
     * 纠纷仍走本地工单，不给 SLA 建错误类型的事项（契约第一阶段只支持商品核实）。</p>
     */
    public static final String REASON_PRODUCT_VERIFICATION = "PRODUCT_VERIFICATION";

    /** 转人工原因，例如 USER_REQUESTED_HUMAN、COMPLAINT、COMPENSATION_OR_DISPUTE */
    private final String reason;

    /** 给人工客服看的问题摘要 */
    private final String summary;

    /** 额外上下文，例如 userId、conversationId、activeIntent 等 */
    private final Map<String, Object> context;

    /**
     * true 表示“暂停机器人、转卖家本人确认”，不创建人工工单。
     *
     * <p>真实渠道里“超出机器人授权”需要的是卖家拍板，而不是一张工单；正式转接
     * （买家明确要求人工、投诉等）仍走工单路径。暂停通过 HANDED_OFF 终态让
     * CustomerServiceAgent 返回 handoffRequested，侧车据此停止自动回复。</p>
     */
    private final boolean pauseOnly;

    /** pauseOnly 场景发给买家的确定性回复；模型不参与，避免措辞漂移出授权口径。 */
    private final String buyerReply;

    public HandoffAction(String reason, String summary, Map<String, Object> context) {
        this(reason, summary, context, false, null);
    }

    public HandoffAction(String reason,
                         String summary,
                         Map<String, Object> context,
                         boolean pauseOnly,
                         String buyerReply) {
        this.reason = reason;
        this.summary = summary;
        this.context = context == null ? Collections.emptyMap() : Map.copyOf(context);
        this.pauseOnly = pauseOnly;
        this.buyerReply = buyerReply;
    }

    public String getReason() {
        return reason;
    }

    public String getSummary() {
        return summary;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public boolean isPauseOnly() {
        return pauseOnly;
    }

    public String getBuyerReply() {
        return buyerReply == null ? "" : buyerReply;
    }

    @Override
    public String toString() {
        return "HandoffAction{" +
                "reason=" + reason +
                ", summary=" + summary +
                ", pauseOnly=" + pauseOnly +
                ", context=" + context +
                "}";
    }
}
