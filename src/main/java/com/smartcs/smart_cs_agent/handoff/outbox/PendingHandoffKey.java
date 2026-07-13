package com.smartcs.smart_cs_agent.handoff.outbox;

/**
 * 人工转接 Outbox 的租户复合键。
 *
 * <p>为什么不继续只传一个 {@code idempotencyKey}？因为不同租户完全可能生成相同的业务幂等键。
 * 把 tenantId 和 idempotencyKey 包成一个不可变对象后，Store 的每次读取、更新和租约操作都必须
 * 显式携带租户，编译器可以帮助我们发现遗漏，而不是仅依赖开发者记忆。</p>
 */
public record PendingHandoffKey(String tenantId, String idempotencyKey) {

    public PendingHandoffKey {
        tenantId = requireText(tenantId, "tenantId");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        requireMaxLength(tenantId, 64, "tenantId");
        requireMaxLength(idempotencyKey, 255, "idempotencyKey");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value.trim();
    }

    private static void requireMaxLength(String value, int maxLength, String field) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
    }
}
