package com.smartcs.smart_cs_agent.persistence.mybatis.row;

public record SlaAssociationRow(
        String tenantId,
        String idempotencyKey,
        String requestId,
        String channel,
        String sellerAccountId,
        String itemId,
        String conversationId,
        String buyerId,
        Long workItemId,
        long createdAt,
        long updatedAt
) {
}
