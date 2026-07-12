package com.smartcs.smart_cs_agent.tools;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 人工转接工具。
 *
 * 【当前做什么？】
 * 1. 默认 memory 模式：生成一个本地模拟工单号，保证 Agent 项目可以单独运行。
 * 2. after-sales-http 模式：调用桌面 demo 项目的售后工单平台，创建真实工单。
 *
 * 【为什么仍然要做成工具？】
 * 因为真实项目里转人工通常会调用工单系统、IM 系统或 CRM 系统。
 * 先做成工具边界，后续替换真实系统时，不需要改 Skill 和 Runtime 的核心流程。
 */
@Component
public class HandoffTools {

    /** 简单递增序号，用于生成演示用工单号 */
    private final AtomicInteger sequence = new AtomicInteger(1000);

    private final HandoffProperties properties;
    private final AfterSalesTicketClient afterSalesTicketClient;

    /**
     * 测试和手动 new 对象时使用的构造函数。
     *
     * 保留它是为了不破坏已有单元测试：如果没有 Spring 注入配置，就继续使用 memory 模式。
     */
    public HandoffTools() {
        this(new HandoffProperties(), null);
    }

    /**
     * Spring 运行时使用的构造函数。
     */
    @Autowired
    public HandoffTools(HandoffProperties properties, AfterSalesTicketClient afterSalesTicketClient) {
        this.properties = properties == null ? new HandoffProperties() : properties;
        this.afterSalesTicketClient = afterSalesTicketClient;
    }

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
        if (properties.isAfterSalesHttpMode() && afterSalesTicketClient != null) {
            return createAfterSalesTicket(userId, conversationId, reason, summary);
        }

        return createMemoryTicket(userId, conversationId, reason, summary, "WAITING_HUMAN_AGENT");
    }

    private HandoffTicketData createAfterSalesTicket(String userId,
                                                     String conversationId,
                                                     String reason,
                                                     String summary) {
        AfterSalesTicketCreateRequest request = new AfterSalesTicketCreateRequest(
                buildTitle(reason, summary),
                buildDescription(userId, conversationId, reason, summary),
                firstNonBlank(userId, "UNKNOWN_USER"),
                inferProductLine(reason, summary),
                inferIssueType(reason, summary),
                inferPriority(reason, summary),
                buildIdempotencyKey(conversationId, reason)
        );

        try {
            AfterSalesTicketCreateResult result = afterSalesTicketClient.createTicket(request);
            return new HandoffTicketData(
                    result.ticketId(),
                    userId,
                    conversationId,
                    reason,
                    summary,
                    result.status(),
                    result.createdAt()
            );
        } catch (RuntimeException e) {
            // 超时只表示“客户端没有拿到结果”，远端可能已经创建了工单。
            // 在可靠 PendingHandoffStore/Outbox 完成前，不能生成一个只存在内存的假工单并向用户宣告成功。
            // 抛出专用异常后，Gateway 会返回技术失败；同一个幂等键会稳定回放该失败，不会立即重复请求远端。
            throw new HandoffTicketCreationException(
                    "Remote handoff result could not be confirmed",
                    e
            );
        }
    }

    private HandoffTicketData createMemoryTicket(String userId,
                                                String conversationId,
                                                String reason,
                                                String summary,
                                                String status) {
        String ticketId = "HD-" + sequence.incrementAndGet();

        return new HandoffTicketData(
                ticketId,
                userId,
                conversationId,
                reason,
                summary,
                status,
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

    private String buildTitle(String reason, String summary) {
        String source = firstNonBlank(summary, reason, "客服问题");
        return limit("Agent 转人工：" + compact(source), 120);
    }

    private String buildDescription(String userId,
                                    String conversationId,
                                    String reason,
                                    String summary) {
        String description = "用户ID：" + firstNonBlank(userId, "UNKNOWN_USER") + "\n" +
                "会话ID：" + firstNonBlank(conversationId, "UNKNOWN_CONVERSATION") + "\n" +
                "转人工原因：" + firstNonBlank(reason, "UNKNOWN_REASON") + "\n" +
                "Agent 摘要：" + firstNonBlank(summary, "用户请求人工客服继续处理。");
        return limit(description, 2000);
    }

    private String inferPriority(String reason, String summary) {
        String text = normalize(reason + " " + summary);
        if (containsAny(text, "投诉", "赔偿", "维权", "差评", "客服经理", "严重")) {
            return "URGENT";
        }
        if (containsAny(text, "着急", "尽快", "催", "无法使用", "不能用", "坏了", "故障", "影响使用")) {
            return "HIGH";
        }
        return "MEDIUM";
    }

    private String inferProductLine(String reason, String summary) {
        String text = normalize(reason + " " + summary);
        if (containsAny(text, "退货", "退款", "换货", "售后")) {
            return "return";
        }
        if (containsAny(text, "蓝牙", "耳机")) {
            return "bluetooth";
        }
        if (containsAny(text, "手表")) {
            return "wearable";
        }
        if (containsAny(text, "充电器", "数据线")) {
            return "accessory";
        }
        if (containsAny(text, "订单", "物流", "快递", "发货", "签收")) {
            return "order";
        }
        if (containsAny(text, "发票", "开票")) {
            return "invoice";
        }
        return "general";
    }

    private String inferIssueType(String reason, String summary) {
        String text = normalize(reason + " " + summary);
        if (containsAny(text, "投诉", "差评", "客服经理")) {
            return "complaint";
        }
        if (containsAny(text, "赔偿", "维权", "纠纷")) {
            return "dispute";
        }
        if (containsAny(text, "退货", "退款", "换货")) {
            return "return";
        }
        if (containsAny(text, "物流", "快递", "发货", "签收")) {
            return "logistics";
        }
        if (containsAny(text, "发票", "开票")) {
            return "invoice";
        }
        if (containsAny(text, "故障", "坏了", "不能用", "无法使用", "质量问题")) {
            return "quality";
        }
        return "handoff";
    }

    private String buildIdempotencyKey(String conversationId, String reason) {
        return "agent:" + safeSegment(conversationId, "unknown-conversation")
                + ":handoff:" + safeSegment(reason, "unknown-reason");
    }

    private String safeSegment(String value, String fallback) {
        String text = firstNonBlank(value, fallback);
        return text.replaceAll("[^A-Za-z0-9_\\-:.]", "-");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(normalize(keyword))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String first, String second) {
        return firstNonBlank(first, second, "");
    }

    private String firstNonBlank(String first, String second, String third) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return third;
    }

    private String compact(String text) {
        return firstNonBlank(text, "").replaceAll("\\s+", " ").trim();
    }

    private String limit(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }
}
