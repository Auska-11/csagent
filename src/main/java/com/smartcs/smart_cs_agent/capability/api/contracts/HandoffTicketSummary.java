package com.smartcs.smart_cs_agent.capability.api.contracts;

import java.util.Map;

/**
 * {@code handoff.request} 的稳定输出契约。
 *
 * <p>旧 HandoffTools 返回 tools.HandoffTicketData，远程 MCP 返回普通 JSON Map。Workflow 和
 * Action Handler 不应该认识这些实现差异，所以 Provider 在边界统一转换成本类。</p>
 *
 * <p>这里只保留自动客服回复真正需要的字段。用户 ID、会话 ID 和完整问题摘要已经由服务端
 * ExecutionContext 与工单系统保存，不必再次进入 Agent Observation，减少不必要的数据暴露。</p>
 */
public final class HandoffTicketSummary {

    private final String ticketId;
    private final String reason;
    private final String status;
    private final String createdAt;

    public HandoffTicketSummary(String ticketId,
                                String reason,
                                String status,
                                String createdAt) {
        this.ticketId = safeText(ticketId);
        this.reason = safeText(reason);
        this.status = safeText(status);
        this.createdAt = safeText(createdAt);
    }

    /** 同时兼容 MCP JSON 常见的 snake_case 和 Java 默认的 camelCase。 */
    public static HandoffTicketSummary fromMap(Object value) {
        if (value instanceof HandoffTicketSummary ticket) {
            return ticket;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new HandoffTicketSummary(
                text(map, "ticket_id", "ticketId"),
                text(map, "reason"),
                text(map, "status"),
                text(map, "created_at", "createdAt")
        );
    }

    public String getTicketId() {
        return ticketId;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    private static String text(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
