package com.smartcs.smart_cs_agent.skill;

/**
 * 客服意图规则集合。
 *
 * 【为什么单独拆出来？】
 * 多个专业 Workflow 都需要“是否退货相关”“是否政策问题”“是否需要转人工”等基础关键词判断。
 * 把无状态规则集中在这里，可以避免每个 Workflow 复制字符串列表。
 *
 * 单独拆出来以后：
 * - 规则更集中，后续补关键词更方便。
 * - Workflow 主流程更短，更容易读。
 * - 单元测试可以直接测规则本身。
 */
public class CustomerServiceIntentRules {

    /**
     * 判断 message 中是否包含任意一个关键词。
     *
     * 这是一个小工具方法，避免在规则判断里写很多重复的 contains。
     */
    public boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断用户消息是否和“退换货/退款”有关。
     */
    public boolean isReturnRelatedMessage(String lowerMessage) {
        return containsAny(lowerMessage,
                "return", "refund",
                "退货", "退款", "换货", "退换",
                "想退", "要退", "退一下", "退这个", "不想要",
                "质量问题");
    }

    /**
     * 判断是否应该转人工。
     */
    public boolean shouldHandoff(String lowerMessage) {
        return containsAny(lowerMessage,
                "人工", "转人工", "人工客服", "真人客服", "找真人", "human agent",
                "投诉", "差评", "太差", "不满意", "生气",
                "赔偿", "补偿", "维权", "律师", "起诉", "12315");
    }

    /**
     * 给转人工原因做一个粗粒度分类。
     *
     * 这个分类会进入人工工单，方便后续人工客服判断优先级。
     */
    public String classifyHandoffReason(String lowerMessage) {
        if (containsAny(lowerMessage, "赔偿", "补偿", "维权", "律师", "起诉", "12315")) {
            return "COMPENSATION_OR_DISPUTE";
        }
        if (containsAny(lowerMessage, "投诉", "差评", "太差", "不满意", "生气", "垃圾")) {
            return "COMPLAINT";
        }
        if (containsAny(lowerMessage, "人工", "转人工", "真人客服", "找真人", "human agent")) {
            return "USER_REQUESTED_HUMAN";
        }
        return "NEEDS_HUMAN_REVIEW";
    }

    /**
     * 判断用户是否在问“政策/FAQ/规则”类问题。
     *
     * 【为什么它要优先于退货申请？】
     * “我要退货”是一个任务，应该进入退货申请流程；
     * “超过七天还能退吗？”是一个政策问题，应该先查知识库。
     */
    public boolean isKnowledgeRelatedMessage(String lowerMessage) {
        if (containsAny(lowerMessage,
                "invoice", "warranty", "policy", "delivery policy", "handoff",
                "发票", "电子发票", "专用发票", "抬头", "税号",
                "保修", "质保", "维修政策", "售后检测",
                "配送范围", "配送政策", "偏远地区", "发货时效", "物流异常",
                "人工客服", "转人工", "投诉规则", "赔偿规则")) {
            return true;
        }

        // 退换货政策问题：包含“七天、政策、规则、能不能退”等表达时才走知识库。
        // 单纯的“我要退货”不会命中这里，仍然会进入退货申请多轮流程。
        return containsAny(lowerMessage,
                "退货政策", "退款政策", "退换货政策", "退款规则", "退货规则",
                "七天", "七天无理由", "超过七天", "还能退", "可以退吗", "能退吗", "能不能退",
                "退货运费", "运费谁承担", "退款多久到账");
    }

    /**
     * 判断用户是否在表达长期偏好。
     *
     * 例如：
     * - “我喜欢蓝牙耳机”
     * - “以后给我推荐智能手表”
     * - “我偏好无线充电器”
     */
    public boolean isPreferenceMemoryMessage(String lowerMessage) {
        return containsAny(lowerMessage,
                "我喜欢", "喜欢", "偏好", "感兴趣", "以后推荐", "下次推荐", "常买",
                "prefer", "favorite", "interested in");
    }
}
