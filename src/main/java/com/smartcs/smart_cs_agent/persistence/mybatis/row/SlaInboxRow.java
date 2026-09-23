package com.smartcs.smart_cs_agent.persistence.mybatis.row;

public record SlaInboxRow(
        String eventId,
        String requestId,
        String payloadHash,
        String disposition,
        long receivedAt
) {
}
