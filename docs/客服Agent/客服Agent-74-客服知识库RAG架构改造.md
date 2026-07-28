# 客服知识库 RAG 架构改造

## 1. 业务边界

知识库只保存相对稳定、可溯源的客服知识：

- `POLICY`：退换货、发票、配送、保修期限、人工转接等确定性规则。
- `TROUBLESHOOTING`：商品使用方法、故障现象和排查步骤。

订单、库存、物流实时状态和退货进度仍通过 Capability/API 查询，不写入 RAG。

## 2. 分层结构

```text
KnowledgeTools（兼容门面）
└── KnowledgeSearchService
    ├── KnowledgeQueryPlanner
    │   └── QueryRewriter
    ├── PolicyRetrievalStrategy
    │   ├── KeywordRetriever
    │   ├── KnowledgeReranker
    │   └── RelevanceGate
    └── TroubleshootingRetrievalStrategy
        ├── KeywordRetriever
        ├── QdrantVectorRetriever
        ├── ResultFusion（RRF + chunkId 去重）
        ├── KnowledgeReranker（可解释规则）
        └── RelevanceGate
```

`KnowledgeTools` 不再负责 Markdown 加载、查询改写、打分、向量索引和重排，只保留旧 Java 调用入口与 Prompt 文本格式化。Retriever 不调用 LLM。

## 3. 完整调用链

政策：

```text
PolicyQaWorkflow / WarrantyQueryWorkflow
  -> CapabilityAction(knowledge.search, knowledge_type=POLICY)
  -> CapabilityActionHandler
  -> CapabilityGateway
  -> Local 或 MCP Provider
  -> KnowledgeSearchService
  -> Planner / QueryRewriter
  -> PolicyRetrievalStrategy
  -> KeywordRetriever（标题/关键词/正文加权 + 元数据过滤）
  -> rule Reranker
  -> strict RelevanceGate
  -> KnowledgeSearchHit
  -> Workflow 使用命中片段和标题/来源做确定性回答
```

故障排查：

```text
TroubleshootingWorkflow
  -> CapabilityAction(knowledge.search, knowledge_type=TROUBLESHOOTING, category/model)
  -> Gateway / Provider / KnowledgeSearchService
  -> KeywordRetriever
  -> local: 只保留关键词候选
     hybrid: QdrantVectorRetriever 独立召回（失败返回空通道）
  -> ResultFusion（RRF、chunkId 去重）
  -> rule Reranker
  -> RelevanceGate
  -> 无可靠证据：追问型号/现象，不调用 LLM
  -> 有可靠证据：LLMAction
  -> LLMActionHandler（统一超时、降级、Trace）
  -> Workflow 输出步骤与知识来源
```

## 4. 文档与 Chunk

Markdown 使用简单 front matter 声明 `documentId`、`knowledgeType`、`productCategory`、`productModel`、`source`、`version`、`effectiveDate`。`KnowledgeChunker` 按二级或三级标题切块，每个 Chunk 有稳定 `chunkId`，Qdrant point 以 `chunkId` 生成稳定 UUID。

政策查询过滤知识类型、指定版本和未到生效日的文档。故障查询还可过滤商品品类和型号。第一版没有实现过期日、政策灰度发布或复杂版本优先级。

## 5. 检索与可靠性

`KeywordRetriever` 是字段加权规则检索，不是 Lucene/BM25。它按标题、人工关键词、正文和精确短语加权，并记录命中字段和词。

混合模式使用 RRF 融合两个排名，按 `chunkId` 去重。`KnowledgeReranker` 是规则重排，综合关键词分、Qdrant 原始余弦相似度、双路一致性和型号精确命中；没有使用 LLM Reranker 或 Cross-Encoder。

Qdrant 原始结果先经过 `vector-min-score`，融合重排后再经过统一 `RelevanceGate`。TopK 非空不等于可靠命中。Gate 后为空时，现有 `CapabilityActionHandler` 继续记录 `knowledge_miss`。

## 6. 模式与迁移

| 模式 | 政策 | 故障排查 | 外部故障 |
|---|---|---|---|
| `local` | 关键词 + 严格 Gate | 关键词 + 融合/重排/Gate | 不访问 Embedding/Qdrant |
| `hybrid` | 仍只走关键词 | 关键词 + Embedding/Qdrant | 只关闭向量通道，关键词继续 |

旧配置 `SMARTCS_RAG_MODE=embedding-qdrant` 暂作为 `hybrid` 别名；推荐迁移到 `SMARTCS_RAG_MODE=hybrid`。新增阈值：

```text
SMARTCS_RAG_POLICY_MIN_SCORE=24
SMARTCS_RAG_TROUBLESHOOTING_MIN_SCORE=24
SMARTCS_RAG_VECTOR_MIN_SCORE=0.72
```

## 7. 当前边界

- 默认测试使用 Fake EmbeddingClient/VectorStore，没有验证真实 Qdrant 的召回准确率、索引容量、并发建索引或线上延迟。
- 当前 Chunk 以 Markdown 标题切分，不包含 token 长度上限、重叠窗口或表格专用切分。
- 规则改写、关键词权重、向量阈值和重排权重来自小规模离线样例，仅能证明回归用例行为，不能解释为线上准确率。
- Qdrant 索引当前在进程首次向量检索时同步确保和写入；大规模知识库应迁移为离线索引流水线，并增加版本别名和原子切换。
- Product model 目前使用规范化精确过滤，尚未处理型号别名、地区版和硬件 revision。
