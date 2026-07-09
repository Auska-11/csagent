# 客服Agent-33-Embedding 向量数据库 RAG 设计

## 1. 为什么继续升级 RAG

前一版 RAG 已经完成了 Markdown 知识库、查询改写、多路召回、简单 rerank 和本地轻量向量分。

这套方案适合本地学习和稳定测试，但它本质上还是“可解释 baseline”：

- 知识库规模变大后，本地遍历所有文档不够像生产方案。
- 字符 n-gram 伪向量只能模拟语义召回，不能真正理解同义表达。
- 面试时如果被追问“真实 embedding 和向量库怎么接”，需要有可运行的第一版实现。

所以本模块新增 `embedding-qdrant` 模式：调用 OpenAI-compatible embedding API，把知识库文档写入 Qdrant，再用查询向量做相似度检索。

## 2. 双模式设计

配置项：

```yaml
smartcs:
  rag:
    mode: ${SMARTCS_RAG_MODE:local}
```

| 模式 | 说明 |
| --- | --- |
| `local` | 默认模式，继续使用本地 Markdown + 关键词 + 轻量向量分，不依赖外部服务 |
| `embedding-qdrant` | 高级模式，调用真实 embedding API，并使用 Qdrant 向量数据库检索 |

默认保留 `local` 的原因：

- 单元测试和 CI 不应该依赖外部模型服务。
- 新同学本地启动项目时，不需要先准备 Qdrant 和 API Key。
- 面试演示可以先展示稳定 baseline，再切换到真实向量库版本讲演进。

## 3. 当前调用链

```text
用户问题
  -> KnowledgeTools.search()
  -> 判断 smartcs.rag.mode
  -> local：走本地查询改写、多路召回、rerank
  -> embedding-qdrant：
       1. 首次检索时把知识文档 embedding 后写入 Qdrant
       2. 对用户问题调用 embedding API
       3. 用查询向量检索 Qdrant
       4. 将命中结果转成 KnowledgeSearchResult
       5. 如果失败或无结果，回退 local RAG
```

核心类：

```text
RagProperties
TextEmbeddingClient
OpenAiTextEmbeddingClient
KnowledgeVectorStore
QdrantKnowledgeVectorStore
VectorKnowledgeHit
KnowledgeTools
```

## 4. 为什么用接口隔离

`KnowledgeTools` 不直接依赖 OpenAI 或 Qdrant 的具体实现，而是依赖两个接口：

```text
TextEmbeddingClient：负责把文本转成向量
KnowledgeVectorStore：负责向量库建表、写入和检索
```

这样做的好处：

- 单元测试可以用 fake embedding 和 fake vector store，不需要真实网络。
- 后续如果换成 pgvector、Milvus 或 Elasticsearch dense vector，上层 RAG 流程不用大改。
- 代码职责更清楚：模型调用、向量存储、知识库检索各自独立。

## 5. Qdrant 数据写入

第一版为了减少依赖，没有引入 Qdrant Java SDK，而是直接使用 Qdrant REST API：

```text
PUT  /collections/{collection}
PUT  /collections/{collection}/points?wait=true
POST /collections/{collection}/points/search
```

每篇 Markdown 文档会写入一个向量点：

```json
{
  "id": "由 documentId 生成的稳定 UUID",
  "vector": [0.01, 0.02, "..."],
  "payload": {
    "documentId": "policy/return_policy.md",
    "category": "policy",
    "title": "退换货政策",
    "content": "..."
  }
}
```

使用稳定 UUID 的原因是：同一篇文档重复写入时会覆盖同一个 point，避免重启后不断产生重复数据。

## 6. 降级策略

真实 embedding 和 Qdrant 都是外部依赖，所以不能让它们影响客服主链路可用性。

当前策略：

- embedding API 调用失败：回退本地 RAG。
- Qdrant 建 collection 失败：回退本地 RAG。
- Qdrant 写入失败：回退本地 RAG。
- Qdrant 查询无结果：回退本地 RAG。

面试时可以强调：

> 高级 RAG 是增强能力，不应该变成新的单点故障。默认 local 保证服务可用，embedding-qdrant 模式失败时回退本地检索，既能演示真实向量库，也保留降级兜底。

## 7. 环境变量

```text
SMARTCS_RAG_MODE=local
SMARTCS_EMBEDDING_BASE_URL=https://api.openai.com/v1
SMARTCS_EMBEDDING_API_KEY=your-api-key
SMARTCS_EMBEDDING_MODEL_NAME=text-embedding-3-small
SMARTCS_EMBEDDING_DIMENSIONS=1536
SMARTCS_QDRANT_BASE_URL=http://localhost:6333
SMARTCS_QDRANT_COLLECTION=smart_cs_knowledge
```

开启真实向量库模式：

```powershell
$env:SMARTCS_RAG_MODE="embedding-qdrant"
$env:SMARTCS_EMBEDDING_API_KEY="your-api-key"
$env:SMARTCS_QDRANT_BASE_URL="http://localhost:6333"
```

Docker Compose 内部访问 Qdrant 时使用：

```text
SMARTCS_QDRANT_BASE_URL=http://qdrant:6333
```

## 8. 验证方式

局部测试：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" "-Dmaven.compiler.fork=false" "-Dtest=KnowledgeToolsTest" test
```

全量测试：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" "-Dmaven.compiler.fork=false" test
```

已补充测试：

- `shouldUseEmbeddingVectorStoreWhenEnabled`：验证高级模式会调用 embedding、初始化向量库并返回向量命中。
- `shouldFallbackToLocalSearchWhenEmbeddingVectorStoreFails`：验证向量库失败时回退本地检索。

## 9. 面试怎么讲

可以这样讲：

> 我把 RAG 做成了渐进式架构：默认 local 模式使用 Markdown、关键词、查询改写和本地轻量向量分，保证测试稳定和本地可运行；高级模式通过 OpenAI-compatible embedding API 生成向量，并写入 Qdrant 做相似度检索。为了避免外部服务影响客服主链路，我把 embedding 和向量库都抽成接口，失败时自动回退本地 RAG。

如果被追问为什么不用 SDK：

> 第一版直接用 Qdrant REST API，主要是减少依赖和构建复杂度，同时能把向量库建 collection、upsert points、search points 的核心流程讲清楚。后续项目规模变大后，可以把 `QdrantKnowledgeVectorStore` 替换成官方 SDK 实现，上层 `KnowledgeTools` 不需要大改。

