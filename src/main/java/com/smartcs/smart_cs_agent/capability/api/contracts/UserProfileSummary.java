package com.smartcs.smart_cs_agent.capability.api.contracts;

import java.util.Map;

/**
 * user.lookup 的稳定输出契约。
 *
 * <p>只包含当前用户查看资料所需的字段，不包含登录 token；手机号在 Provider 边界脱敏，避免完整
 * 号码进入 Workflow 回复和 Trace。</p>
 */
public final class UserProfileSummary {

    private final String userId;
    private final String name;
    private final String phoneMasked;

    public UserProfileSummary(String userId, String name, String phoneMasked) {
        this.userId = safeText(userId);
        this.name = safeText(name);
        this.phoneMasked = maskPhone(phoneMasked);
    }

    /** 将远程 MCP JSON 恢复为稳定契约，并在远端返回完整手机号时再次执行脱敏。 */
    public static UserProfileSummary fromMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new UserProfileSummary(
                text(map, "user_id", "userId"),
                text(map, "name"),
                text(map, "phone_masked", "phoneMasked", "phone")
        );
    }

    public String getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getPhoneMasked() {
        return phoneMasked;
    }

    public static String maskPhone(String phone) {
        String value = safeText(phone).trim();
        if (value.isBlank() || value.contains("*")) {
            return value;
        }
        if (value.length() < 8) {
            return "****";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private static String text(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
