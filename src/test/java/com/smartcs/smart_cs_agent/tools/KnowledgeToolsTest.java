package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeChunk;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchProfile;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeSearchResponse;
import com.smartcs.smart_cs_agent.knowledge.model.KnowledgeType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
        assertTrue(result.getRerankReason().contains("ruleReranker"));
        assertTrue(result.toPromptText().contains("排序原因"));
    }

    @Test
    void localModeShouldUseOnlyKeywordRetrieval() {
        KnowledgeTools knowledgeTools = new KnowledgeTools(List.of(
                new KnowledgeDocument(
                        "logistics/delivery_policy.md",
                        "logistics",
                        "配送政策",
                        "正常情况下，订单会在 24 小时内发货。用户可以通过物流单号查询配送进度。",
                        List.of("配送", "物流", "发货", "时效")
                )
        ));

        List<KnowledgeSearchResult> results = knowledgeTools.search("什么时候送到", 3);

        assertFalse(results.isEmpty());
        KnowledgeSearchResult result = results.get(0);
        assertEquals("logistics/delivery_policy.md", result.getDocumentId());
        assertEquals(0, result.getVectorScore());
        assertTrue(result.getRetrievalChannels().contains("keyword"));
        assertTrue(result.toPromptText().contains("向量分"));
    }

    @Test
    void shouldFuseAndDeduplicateKeywordAndVectorResultsInHybridMode() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient();
        FakeVectorStore vectorStore = new FakeVectorStore(false, 0.91);
        KnowledgeTools knowledgeTools = new KnowledgeTools(
                List.of(troubleshootingDocument()),
                properties,
                embeddingClient,
                vectorStore
        );

        List<KnowledgeSearchResult> results = knowledgeTools.search(
                "耳机听着听着只剩左边响了",
                3,
                KnowledgeSearchProfile.troubleshooting("headphones", "Pro Max")
        );

        assertEquals(2, embeddingClient.callCount);
        assertTrue(vectorStore.collectionEnsured);
        assertTrue(vectorStore.chunksUpserted);
        assertEquals(KnowledgeType.TROUBLESHOOTING, vectorStore.lastFilter.knowledgeType());
        assertEquals("headphones", vectorStore.lastFilter.productCategory());
        assertEquals("Pro Max", vectorStore.lastFilter.productModel());
        assertEquals(1, results.size());
        KnowledgeSearchResult result = results.get(0);
        assertEquals("troubleshooting/headphones.md", result.getDocumentId());
        assertEquals("troubleshooting/headphones.md#chunk-1", result.getChunkId());
        assertTrue(result.getMatchedFields().contains("embedding_vector"));
        assertTrue(result.getRetrievalChannels().containsAll(List.of("keyword", "vector")));
        assertTrue(result.getRerankReason().contains("dualChannelBonus=12.0"));
    }

    @Test
    void shouldFallbackToKeywordWhenEmbeddingOrQdrantFails() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        KnowledgeTools knowledgeTools = new KnowledgeTools(
                List.of(troubleshootingDocument()),
                properties,
                new FakeEmbeddingClient(),
                new FakeVectorStore(true, 0.91)
        );

        KnowledgeSearchResponse response = knowledgeTools.searchWithDiagnostics(
                "耳机一边响一边没声",
                3,
                KnowledgeSearchProfile.troubleshooting("headphones", "Pro Max")
        );
        List<KnowledgeSearchResult> results = response.results();

        assertFalse(results.isEmpty());
        assertEquals("troubleshooting/headphones.md", results.get(0).getDocumentId());
        assertFalse(results.get(0).getMatchedFields().contains("embedding_vector"));
        assertEquals(List.of("keyword"), results.get(0).getRetrievalChannels());
        assertEquals(1, response.diagnostics().size());
        assertEquals("vector_retrieval_degraded", response.diagnostics().get(0).eventName());
        assertEquals("index", response.diagnostics().get(0).stage());
        assertEquals("IllegalStateException", response.diagnostics().get(0).errorType());
        assertEquals("hybrid", response.diagnostics().get(0).profile());
        assertEquals(KnowledgeType.TROUBLESHOOTING, response.diagnostics().get(0).knowledgeType());
    }

    @Test
    void shouldApplyProductCategoryAndModelFilters() {
        KnowledgeDocument headphones = troubleshootingDocument();
        KnowledgeDocument watch = new KnowledgeDocument(
                "troubleshooting/watch.md",
                "troubleshooting",
                "智能手表 S2",
                "## 无法充电\n关键词：无法充电, 充电触点\n清洁磁吸底座和充电触点。",
                List.of("手表"),
                KnowledgeType.TROUBLESHOOTING,
                "watch",
                "S2",
                "手表 S2 手册",
                "1.0",
                null
        );
        KnowledgeTools tools = new KnowledgeTools(List.of(headphones, watch));

        List<KnowledgeSearchResult> results = tools.search(
                "无法充电",
                3,
                KnowledgeSearchProfile.troubleshooting("watch", "S2")
        );

        assertEquals(1, results.size());
        assertEquals("troubleshooting/watch.md", results.get(0).getDocumentId());
        assertEquals("S2", results.get(0).getProductModel());
    }

    @Test
    void shouldFilterPolicyVersionAndFutureEffectiveDate() {
        KnowledgeDocument current = new KnowledgeDocument(
                "policy/current_invoice.md",
                "policy",
                "当前发票政策",
                "电子发票会在 1 到 3 个工作日内开具。",
                List.of("发票", "电子发票"),
                KnowledgeType.POLICY,
                "",
                "",
                "当前政策来源",
                "2026.1",
                LocalDate.of(2026, 1, 1)
        );
        KnowledgeDocument future = new KnowledgeDocument(
                "policy/future_invoice.md",
                "policy",
                "未来发票政策",
                "电子发票将在当天开具。",
                List.of("发票", "电子发票"),
                KnowledgeType.POLICY,
                "",
                "",
                "未来政策来源",
                "2026.1",
                LocalDate.of(2099, 1, 1)
        );
        KnowledgeTools tools = new KnowledgeTools(List.of(current, future));

        List<KnowledgeSearchResult> results = tools.search(
                "发票什么时候开",
                3,
                new KnowledgeSearchProfile(KnowledgeType.POLICY, "", "", "2026.1")
        );

        assertEquals(1, results.size());
        assertEquals("policy/current_invoice.md", results.get(0).getDocumentId());
        assertEquals("2026.1", results.get(0).getVersion());
    }

    @Test
    void shouldRejectUnreliableKeywordAndVectorResults() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        FakeVectorStore vectorStore = new FakeVectorStore(false, 0.4);
        KnowledgeTools tools = new KnowledgeTools(
                List.of(troubleshootingDocument()),
                properties,
                new FakeEmbeddingClient(),
                vectorStore
        );

        KnowledgeSearchResponse response = tools.searchWithDiagnostics(
                "完全无关的问题",
                3,
                KnowledgeSearchProfile.troubleshooting("headphones", "Pro Max")
        );

        assertTrue(response.results().isEmpty());
        assertTrue(response.diagnostics().isEmpty());
    }

    @Test
    void localModeShouldNeverAccessEmbeddingOrVectorStore() {
        RagProperties properties = new RagProperties();
        properties.setMode("local");
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient();
        FakeVectorStore vectorStore = new FakeVectorStore(false, 0.91);
        KnowledgeTools tools = new KnowledgeTools(
                List.of(troubleshootingDocument()),
                properties,
                embeddingClient,
                vectorStore
        );

        KnowledgeSearchResponse response = tools.searchWithDiagnostics(
                "耳机只剩左边响",
                3,
                KnowledgeSearchProfile.troubleshooting("headphones", "Pro Max")
        );

        assertFalse(response.results().isEmpty());
        assertTrue(response.diagnostics().isEmpty());
        assertEquals(0, embeddingClient.callCount);
        assertFalse(vectorStore.collectionEnsured);
    }

    @Test
    void shouldRetrieveOralFaultExpressionFromChunkedManual() {
        KnowledgeTools tools = new KnowledgeTools();

        List<KnowledgeSearchResult> results = tools.search(
                "耳机听着听着只剩左边响了",
                3,
                KnowledgeSearchProfile.troubleshooting("headphones", "Pro Max")
        );

        assertFalse(results.isEmpty());
        KnowledgeSearchResult first = results.get(0);
        assertEquals("troubleshooting/headphones_pro_max.md", first.getDocumentId());
        assertTrue(first.getChunkId().contains("#chunk-"));
        assertTrue(first.getTitle().contains("单侧声道无输出"));
        assertTrue(first.getSnippet().contains("重新配对左右耳"));
        assertTrue(first.getSource().contains("用户手册"));
    }

    private static class FakeEmbeddingClient implements TextEmbeddingClient {

        private int callCount;

        @Override
        public List<Double> embed(String text) {
            callCount++;
            return List.of(0.1, 0.2, 0.3);
        }
    }

    private static class FakeVectorStore implements KnowledgeVectorStore {

        private final boolean fail;
        private final double score;
        private boolean collectionEnsured;
        private boolean chunksUpserted;
        private KnowledgeVectorFilter lastFilter;

        private FakeVectorStore(boolean fail, double score) {
            this.fail = fail;
            this.score = score;
        }

        @Override
        public void ensureCollection(int dimensions) {
            if (fail) {
                throw new IllegalStateException("qdrant unavailable");
            }
            collectionEnsured = dimensions == 3;
        }

        @Override
        public void upsertChunks(List<KnowledgeChunk> chunks, List<List<Double>> embeddings) {
            chunksUpserted = !chunks.isEmpty() && !embeddings.isEmpty();
        }

        @Override
        public List<VectorKnowledgeHit> search(List<Double> queryEmbedding,
                                               int topK,
                                               KnowledgeVectorFilter filter) {
            lastFilter = filter;
            return List.of(new VectorKnowledgeHit(
                    "policy/return_policy.md",
                    "troubleshooting/headphones.md#chunk-1",
                    "TROUBLESHOOTING",
                    "troubleshooting",
                    "headphones",
                    "Pro Max",
                    "蓝牙耳机 Pro Max / 单侧无声",
                    "耳机手册",
                    "1.0",
                    "",
                    "单侧声道无输出时，请重新配对左右耳并重置蓝牙连接。",
                    score
            ));
        }
    }

    private static KnowledgeDocument troubleshootingDocument() {
        return new KnowledgeDocument(
                "troubleshooting/headphones.md",
                "troubleshooting",
                "蓝牙耳机 Pro Max",
                "## 单侧无声\n关键词：单侧无声, 一边响, 左边响\n单侧声道无输出时，请重新配对左右耳并重置蓝牙连接。",
                List.of("耳机", "Pro Max"),
                KnowledgeType.TROUBLESHOOTING,
                "headphones",
                "Pro Max",
                "耳机手册",
                "1.0",
                LocalDate.of(2026, 1, 1)
        );
    }
}
