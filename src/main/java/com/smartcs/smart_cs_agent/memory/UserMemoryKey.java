package com.smartcs.smart_cs_agent.memory;

/** 用户偏好存储的完整作用域；两个租户中的同名用户是不同的 owner。 */
public record UserMemoryKey(String tenantId, String userId) {
    public UserMemoryKey {
        tenantId = requireText(tenantId, "tenantId");
        userId = requireText(userId, "userId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be empty");
        }
        return value.trim();
    }
}
