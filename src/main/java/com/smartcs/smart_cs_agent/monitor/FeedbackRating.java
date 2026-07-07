package com.smartcs.smart_cs_agent.monitor;

/**
 * 用户反馈评分。
 *
 * 【为什么用枚举？】
 * 如果直接存字符串，前端可能传 helpful、like、good 等不同写法，
 * 后端统计时就很容易出现脏数据。
 *
 * 枚举可以把允许的值固定下来：
 * - HELPFUL：用户认为这次回复有帮助。
 * - UNHELPFUL：用户认为这次回复没有帮助。
 */
public enum FeedbackRating {
    HELPFUL,
    UNHELPFUL;

    /**
     * 把前端传来的字符串转换成枚举。
     *
     * 这里统一转成大写，是为了让接口对大小写稍微宽容一些。
     */
    public static FeedbackRating from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Rating cannot be empty");
        }

        try {
            return FeedbackRating.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Rating must be HELPFUL or UNHELPFUL");
        }
    }

    public boolean isHelpful() {
        return this == HELPFUL;
    }
}
