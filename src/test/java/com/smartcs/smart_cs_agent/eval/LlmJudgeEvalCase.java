package com.smartcs.smart_cs_agent.eval;

import java.util.List;

/**
 * LLM-as-Judge 评测用例。
 *
 * 这个类只在测试评测模块中使用，所以放在 src/test/java。
 * 每个用例保存一条“候选客服回复”和它应该满足的参考事实。
 */
class LlmJudgeEvalCase {

    /** 用例 ID，方便报告定位问题 */
    public String id;

    /** 用户原始问题 */
    public String userMessage;

    /** 被评测的客服回复 */
    public String candidateReply;

    /** 参考事实，Judge 应该根据这些事实判断回复是否忠实 */
    public List<String> referenceFacts;

    /** 期望 Judge 最终是否通过 */
    public boolean expectedPass;

    /**
     * 测试用的 Fake Judge 返回值。
     *
     * 第一版不调用真实模型，避免测试依赖网络和 API Key。
     * 后续接真实模型时，可以移除这个字段，改成真实调用 Judge Model。
     */
    public String fakeJudgeJson;
}
