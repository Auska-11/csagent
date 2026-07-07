package com.smartcs.smart_cs_agent.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KnowledgeTools 单元测试。
 *
 * 这里验证的是第一版“轻量 RAG”能力：
 * - 用户问政策问题时，能命中正确 Markdown 文档
 * - 找不到内容时，不返回不可靠结果
 * - 给 LLM 的文本里包含“不要编造”的约束
 */
class KnowledgeToolsTest {

    @Test
    void shouldFindReturnPolicyForOverSevenDaysQuestion() {
        KnowledgeTools knowledgeTools = new KnowledgeTools(List.of(
                new KnowledgeDocument(
                        "policy/return_policy.md",
                        "policy",
                        "退换货政策",
                        "超过 7 天后，不再默认支持七天无理由退货。若商品存在质量问题，可以继续申请售后检测。",
                        List.of("退货", "七天", "超过七天", "质量问题")
                ),
                new KnowledgeDocument(
                        "policy/invoice_policy.md",
                        "policy",
                        "发票政策",
                        "电子发票会在 1 到 3 个工作日内开具。",
                        List.of("发票", "电子发票")
                )
        ));

        List<KnowledgeSearchResult> results = knowledgeTools.search("超过七天还能退吗", 3);

        assertFalse(results.isEmpty());
        assertEquals("policy/return_policy.md", results.get(0).getDocumentId());
        assertTrue(results.get(0).getSnippet().contains("七天"));
    }

    @Test
    void shouldFormatSearchResultWithNoHallucinationInstruction() {
        KnowledgeTools knowledgeTools = new KnowledgeTools(List.of(
                new KnowledgeDocument(
                        "policy/invoice_policy.md",
                        "policy",
                        "发票政策",
                        "电子发票会在 1 到 3 个工作日内开具。",
                        List.of("发票", "电子发票")
                )
        ));

        String promptText = knowledgeTools.searchKnowledge("发票什么时候开");

        assertTrue(promptText.contains("发票政策"));
        assertTrue(promptText.contains("不要编造"));
    }

    @Test
    void shouldReturnEmptyWhenNoReliableResult() {
        KnowledgeTools knowledgeTools = new KnowledgeTools(List.of(
                new KnowledgeDocument(
                        "policy/invoice_policy.md",
                        "policy",
                        "发票政策",
                        "电子发票会在 1 到 3 个工作日内开具。",
                        List.of("发票", "电子发票")
                )
        ));

        List<KnowledgeSearchResult> results = knowledgeTools.search("你们公司创始人是谁", 3);

        assertTrue(results.isEmpty());
    }

    @Test
    void shouldRewriteFaultQuestionToWarrantyPolicy() {
        KnowledgeTools knowledgeTools = new KnowledgeTools(List.of(
                new KnowledgeDocument(
                        "policy/warranty_policy.md",
                        "policy",
                        "保修政策",
                        "正常使用过程中出现的非人为性能故障，可以申请保修检测。检测确认属于质量问题后，可提供维修或换货方案。",
                        List.of("保修", "质保", "维修", "售后检测", "质量问题")
                ),
                new KnowledgeDocument(
                        "policy/invoice_policy.md",
                        "policy",
                        "发票政策",
                        "电子发票会在 1 到 3 个工作日内开具。",
                        List.of("发票", "电子发票")
                )
        ));

        List<KnowledgeSearchResult> results = knowledgeTools.search("耳机坏了能修吗", 3);

        assertFalse(results.isEmpty());
        assertEquals("policy/warranty_policy.md", results.get(0).getDocumentId());
        assertTrue(results.get(0).getQueryVariants().contains("保修 质保 维修 售后检测 质量问题 故障"));
        assertTrue(results.get(0).getMatchedTerms().contains("维修"));
    }

    @Test
    void shouldExposeMatchedFieldsAndRerankReason() {
        KnowledgeTools knowledgeTools = new KnowledgeTools(List.of(
                new KnowledgeDocument(
                        "policy/invoice_policy.md",
                        "policy",
                        "发票政策",
                        "增值税专用发票需要提供企业名称、税号等资料。",
                        List.of("发票", "专用发票", "税号")
                )
        ));

        List<KnowledgeSearchResult> results = knowledgeTools.search("专票要填什么税号", 3);

        assertFalse(results.isEmpty());
        KnowledgeSearchResult result = results.get(0);
        assertTrue(result.getMatchedFields().contains("keywords"));
        assertTrue(result.getMatchedFields().contains("content"));
        assertTrue(result.getRerankReason().contains("keywords="));
        assertTrue(result.toPromptText().contains("排序原因"));
    }
}
