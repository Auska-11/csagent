package com.smartcs.smart_cs_agent.skill;

/**
 * 客服槽位抽取器。
 *
 * 【什么是槽位？】
 * 槽位就是完成一个任务必须收集的信息。
 * 例如：
 * - 查询订单需要 order_id
 * - 查询退货进度需要 return_id
 * - 申请退货需要 order_id + return_reason
 *
 * 以前这些提取逻辑都写在 CustomerServiceSkill 里，
 * 会让 Skill 同时负责“判断下一步”和“从文本里抠字段”。
 * 现在把它拆出来，让 CustomerServiceSkill 更专注于编排 Action。
 */
public class CustomerServiceSlotExtractor {

    /**
     * 从用户消息中提取订单号。
     *
     * 当前支持 ORD-001 这种固定格式。
     * 后续可以升级为正则表达式，支持 ORD-0001、ord001 等更多格式。
     */
    public String extractOrderId(String message) {
        String upper = message.toUpperCase();
        int idx = upper.indexOf("ORD-");
        if (idx >= 0 && idx + 7 <= upper.length()) {
            String num = upper.substring(idx + 4, idx + 7);
            try {
                Integer.parseInt(num);
                return "ORD-" + num;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从用户消息中提取退换货单号。
     *
     * 当前支持 RET-001 这种固定格式。
     */
    public String extractReturnId(String message) {
        String upper = message.toUpperCase();
        int idx = upper.indexOf("RET-");
        if (idx >= 0 && idx + 7 <= upper.length()) {
            String num = upper.substring(idx + 4, idx + 7);
            try {
                Integer.parseInt(num);
                return "RET-" + num;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 尝试从用户消息中提取退货原因。
     *
     * 第一版先使用简单规则：
     * 1. 去掉订单号，例如 ORD-004
     * 2. 去掉“我要退货”“申请退款”这类意图词
     * 3. 剩下的内容如果还有意义，就当作原因
     */
    public String extractReturnReason(String message, String orderId) {
        String cleaned = message;

        if (orderId != null) {
            cleaned = cleaned.replace(orderId, " ");
            cleaned = cleaned.replace(orderId.toLowerCase(), " ");
            cleaned = cleaned.replace(orderId.toUpperCase(), " ");
        }

        String[] noiseWords = {
                "我想退货", "我要退货", "想要退货", "想退货", "要退货",
                "我想退款", "我要退款", "想要退款", "想退款", "要退款",
                "我想换货", "我要换货", "想要换货", "想换货", "要换货",
                "我想退", "我要退", "想退", "要退", "退一下",
                "我想", "我要", "需要", "帮我", "申请",
                "退换货", "退货", "退款", "换货", "退换",
                "这个订单", "订单", "因为", "原因是",
                "return", "refund", "reason", "please"
        };

        for (String noiseWord : noiseWords) {
            cleaned = cleaned.replace(noiseWord, " ");
        }

        cleaned = cleaned
                .replaceAll("[，。,.！!？?：:；;、()（）\\[\\]]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (!hasText(cleaned)) {
            return null;
        }
        // 清洗后如果只剩下“退”这种动词，说明用户还没有真正说明原因。
        // 但“不想要了”是一个有效原因，不能因为包含“想要”两个字就误删。
        if ("退".equals(cleaned)) {
            return null;
        }
        return cleaned;
    }

    /**
     * 提取商品搜索关键词。
     *
     * 例如：
     * - “蓝牙耳机多少钱” -> “耳机”或“蓝牙”
     * - “有什么产品推荐” -> 空字符串，表示搜索全部商品
     */
    public String extractProductKeyword(String message) {
        String lowerMessage = message.toLowerCase();
        String[] keywords = {"耳机", "手表", "充电器", "数据线", "蓝牙", "智能", "无线",
                "headset", "watch", "charger", "cable", "bluetooth", "smart", "wireless"};
        for (String keyword : keywords) {
            if (lowerMessage.contains(keyword)) {
                return keyword;
            }
        }
        if (containsAny(lowerMessage, "推荐", "product", "产品", "商品")) {
            return "";
        }
        return null;
    }

    /**
     * 提取用户长期商品偏好。
     *
     * 第一版先使用白名单短语，而不是复杂 NLP：
     * - 行为稳定，方便测试；
     * - 对新手来说更容易理解；
     * - 后续可以替换成 LLM 抽取或更完整的用户画像服务。
     */
    public String extractProductPreference(String message) {
        String lowerMessage = message.toLowerCase();

        String[] phrasePreferences = {
                "蓝牙耳机", "智能手表", "无线充电器", "type-c 数据线", "type-c数据线",
                "耳机", "手表", "充电器", "数据线",
                "bluetooth headset", "smart watch", "wireless charger", "type-c cable",
                "headset", "watch", "charger", "cable"
        };

        for (String preference : phrasePreferences) {
            if (lowerMessage.contains(preference.toLowerCase())) {
                return normalizeProductPreference(preference);
            }
        }

        return extractProductKeyword(message);
    }

    private String normalizeProductPreference(String preference) {
        if ("type-c数据线".equalsIgnoreCase(preference)) {
            return "Type-C 数据线";
        }
        if ("type-c cable".equalsIgnoreCase(preference)) {
            return "Type-C 数据线";
        }
        if ("bluetooth headset".equalsIgnoreCase(preference) || "headset".equalsIgnoreCase(preference)) {
            return "蓝牙耳机";
        }
        if ("smart watch".equalsIgnoreCase(preference) || "watch".equalsIgnoreCase(preference)) {
            return "智能手表";
        }
        if ("wireless charger".equalsIgnoreCase(preference) || "charger".equalsIgnoreCase(preference)) {
            return "无线充电器";
        }
        if ("cable".equalsIgnoreCase(preference)) {
            return "数据线";
        }
        return preference;
    }

    public boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
