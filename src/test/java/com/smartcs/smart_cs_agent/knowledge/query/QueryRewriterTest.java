package com.smartcs.smart_cs_agent.knowledge.query;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改写器触发词收窄（客服Agent-76 §6.2 修复点 2 第一层）：
 * 单字"退/换"会让"以旧换新""积分兑换"等非政策话术也追加退货政策变体，
 * 是幻觉类场景必然召回无关政策文档的第一层根因。
 */
class QueryRewriterTest {

    private static final String RETURN_VARIANT =
            "退货 退款 换货 七天无理由 超过七天 质量问题 运费 售后检测";

    private final QueryRewriter rewriter = new QueryRewriter();

    @Test
    void nonPolicyPhrasesWithSingleCharVerbMustNotAddReturnVariant() {
        assertFalse(rewriter.rewrite("以旧换新怎么算", KnowledgeType.POLICY)
                .variants().contains(RETURN_VARIANT));
        assertFalse(rewriter.rewrite("积分兑换的规则是什么", KnowledgeType.POLICY)
                .variants().contains(RETURN_VARIANT));
        assertFalse(rewriter.rewrite("换季活动什么时候开始", KnowledgeType.POLICY)
                .variants().contains(RETURN_VARIANT));
    }

    @Test
    void genuineReturnPhrasesMustKeepReturnVariant() {
        assertTrue(rewriter.rewrite("退货政策里质检要多久", KnowledgeType.POLICY)
                .variants().contains(RETURN_VARIANT));
        assertTrue(rewriter.rewrite("超过七天还能退吗", KnowledgeType.POLICY)
                .variants().contains(RETURN_VARIANT));
        assertTrue(rewriter.rewrite("退货运费谁承担", KnowledgeType.POLICY)
                .variants().contains(RETURN_VARIANT));
        assertTrue(rewriter.rewrite("我想退款", KnowledgeType.POLICY)
                .variants().contains(RETURN_VARIANT));
    }

    @Test
    void troubleshootingRewriteStaysUnchangedForSymptomPhrases() {
        assertTrue(rewriter.rewrite("耳机左边没声音", KnowledgeType.TROUBLESHOOTING)
                .variants().stream().anyMatch(v -> v.contains("单侧无声")));
        assertTrue(rewriter.rewrite("蓝牙耳机连不上", KnowledgeType.TROUBLESHOOTING)
                .variants().stream().anyMatch(v -> v.contains("无法配对")));
    }
}
