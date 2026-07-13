package com.smartcs.smart_cs_agent.handoff.admin;

/** 允许 ADMIN API 返回的最小售后工单投影。 */
public record PendingHandoffAdminTicketResponse(
        String ticketId,
        String status,
        String createdAt
) {
}
