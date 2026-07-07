package com.smartcs.smart_cs_agent.tools;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 人工转接工具。
 *
 * 【当前第一版做什么？】
 * 这里不接真实客服系统，而是在内存里生成一个模拟工单号。
 *
 * 【为什么仍然要做成工具？】
 * 因为真实项目里转人工通常会调用工单系统、IM 系统或 CRM 系统。
 * 先做成工具边界，后续替换真实系统时，不需要改 Skill 和 Runtime 的核心流程。
 */
@Component
public class HandoffTools {

    /** 简单递增序号，用于生成演示用工单号 */
    private final AtomicInteger sequence = new AtomicInteger(1000);

    /**
     * 创建人工转接工单。
     *
     * @param userId 用户 ID
     * @param conversationId 会话 ID
     * @param reason 转人工原因
     * @param summary 给人工看的摘要
     * @return 工单结构化信息
     */
    public HandoffTicketData createHandoffTicket(String userId,
                                                 String conversationId,
                                                 String reason,
                                                 String summary) {
        String ticketId = "HD-" + sequence.incrementAndGet();

        return new HandoffTicketData(
                ticketId,
                userId,
                conversationId,
                reason,
                summary,
                "WAITING_HUMAN_AGENT",
                LocalDateTime.now().toString()
        );
    }

    /**
     * 把工单结构整理成适合 Trace 和 Prompt 阅读的文本。
     */
    public String formatTicket(HandoffTicketData ticket) {
        return "已创建人工转接工单：\n" +
                "工单号：" + ticket.getTicketId() + "\n" +
                "用户：" + ticket.getUserId() + "\n" +
                "会话：" + ticket.getConversationId() + "\n" +
                "原因：" + ticket.getReason() + "\n" +
                "状态：" + ticket.getStatus() + "\n" +
                "摘要：" + ticket.getSummary();
    }
}
