package com.smartcs.smart_cs_agent.monitor;

/**
 * 用户负反馈对应的问题类型。
 *
 * 【为什么要做问题类型分类？】
 * 只知道“用户点了无用”还不够，面试或复盘时还需要回答：
 * - 是不是回复缺少关键信息？
 * - 是不是做了危险承诺？
 * - 是不是泄露了内部工具词？
 * - 是不是语气让用户不舒服？
 *
 * 第一版先用规则分类，保证结果稳定、测试容易写。
 * 后续如果接入真实线上数据，可以把这里升级为 LLM 分类或人工标注体系。
 */
public enum FeedbackIssueType {

    /** 回复缺少订单号、时效、政策条件等关键信息 */
    MISSING_KEY_INFO,

    /** 回复中可能承诺了“一定退款”“保证赔偿”等不安全内容 */
    UNSAFE_PROMISE,

    /** 政策说明不清楚，例如退货、发票、保修、配送规则讲得模糊 */
    POLICY_UNCLEAR,

    /** 回复暴露了 Tool、tool_ 等内部实现细节 */
    TOOL_OR_INTERNAL_LEAK,

    /** 用户反馈语气、态度、体验不好 */
    TONE_OR_EXPERIENCE,

    /** 暂时无法明确归类的问题 */
    OTHER
}
