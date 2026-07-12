package com.smartcs.smart_cs_agent.agent.context;

/**
 * 会话在存储层中的完整身份。
 *
 * <p>只用 {@code conversationId} 做 Map 的 key 会有一个安全问题：如果两个用户碰巧使用了
 * 相同 ID，他们可能读到或覆盖彼此的状态。复合键把租户、用户和会话 ID 一起作为边界，
 * 因而同名会话在不同用户或不同租户下仍然是两个完全独立的会话。</p>
 *
 * <p>使用 record 是因为它天然不可变，并且 Java 会自动生成可靠的 equals/hashCode。
 * ConcurrentHashMap 依赖这两个方法判断是不是同一个 key。</p>
 */
public record ConversationKey(String tenantId, String userId, String conversationId) {

    public static final String DEFAULT_TENANT_ID = "default";

    public ConversationKey {
        tenantId = requireText(tenantId, "tenantId");
        userId = requireText(userId, "userId");
        conversationId = requireText(conversationId, "conversationId");
    }

    /** 从一轮聊天请求构造存储键，避免调用方漏传其中某个身份字段。 */
    public static ConversationKey from(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request cannot be null");
        }
        return new ConversationKey(
                request.getTenantId(),
                request.getUserId(),
                request.getConversationId()
        );
    }

    /** Demo 和旧调用方都属于默认租户，用这个工厂方法可以少写一个魔法字符串。 */
    public static ConversationKey defaultTenant(String userId, String conversationId) {
        return new ConversationKey(DEFAULT_TENANT_ID, userId, conversationId);
    }

    /**
     * 生成可放进 traceId、幂等键等字符串字段的无歧义作用域。
     *
     * <p>不能简单使用 {@code tenant:user:conversation}，因为 ID 自己也可能包含冒号。
     * 长度前缀让 {@code 3:abc4:defg} 可以被唯一解释，不会让两个不同复合键意外共享幂等记录。</p>
     */
    public String toScopedId() {
        return segment(tenantId) + segment(userId) + segment(conversationId);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be empty");
        }
        return value.trim();
    }

    private static String segment(String value) {
        return value.length() + ":" + value;
    }
}
