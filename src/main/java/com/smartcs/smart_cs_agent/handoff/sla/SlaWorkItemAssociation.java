package com.smartcs.smart_cs_agent.handoff.sla;

import java.util.Objects;

/**
 * requestId 与本地 pending handoff 操作的持久化关联。
 *
 * <p>建单 HTTP 调用之前先落这条记录，回调若先于创建响应到达，也能按 requestId 找到
 * 原商品、原买家、原会话并校验归属；远端 workItemId 在拿到响应（或回调先到）后回填。</p>
 */
public record SlaWorkItemAssociation(
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

    public SlaWorkItemAssociation {
        requireText(tenantId, "tenantId");
        requireText(idempotencyKey, "idempotencyKey");
        requireText(requestId, "requestId");
        requireText(channel, "channel");
        requireText(sellerAccountId, "sellerAccountId");
        requireText(itemId, "itemId");
        requireText(conversationId, "conversationId");
        requireText(buyerId, "buyerId");
        tenantId = tenantId.trim();
    }

    /**
     * 回调坐标与建单时持久化的坐标是否一致。workItemId 不参与比较（允许回调先回填）。
     */
    public boolean matchesCoordinates(String expectedChannel,
                                       String expectedSellerAccountId,
                                       String expectedItemId,
                                       String expectedConversationId,
                                       String expectedBuyerId) {
        return Objects.equals(channel, expectedChannel)
                && Objects.equals(sellerAccountId, expectedSellerAccountId)
                && Objects.equals(itemId, expectedItemId)
                && Objects.equals(conversationId, expectedConversationId)
                && Objects.equals(buyerId, expectedBuyerId);
    }

    public SlaWorkItemAssociation withWorkItemId(long newWorkItemId) {
        return new SlaWorkItemAssociation(
                tenantId,
                idempotencyKey,
                requestId,
                channel,
                sellerAccountId,
                itemId,
                conversationId,
                buyerId,
                newWorkItemId,
                createdAt,
                System.currentTimeMillis()
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }
}
