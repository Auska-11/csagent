package com.smartcs.smart_cs_agent.persistence.mybatis.row;

public record PendingHandoffKeyRow(String tenantId, String idempotencyKey) {
}
