package com.smartcs.smart_cs_agent.persistence.mybatis.row;

public record SlaResultRow(
        String requestId,
        long workItemId,
        int version,
        String scope,
        String outcome,
        String content,
        String recordedBy,
        String recordedAt,
        long updatedAt
) {
}
