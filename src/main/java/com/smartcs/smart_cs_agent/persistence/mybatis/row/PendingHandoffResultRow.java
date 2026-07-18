package com.smartcs.smart_cs_agent.persistence.mybatis.row;

public record PendingHandoffResultRow(
        String ticketId,
        String ticketStatus,
        String ticketCreatedAt
) {
}
