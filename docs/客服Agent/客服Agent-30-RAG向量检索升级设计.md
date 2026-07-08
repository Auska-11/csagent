# 客服Agent-30-RAG 向量检索升级设计

## 1. 为什么升级 RAG 检索

早期知识库检索主要依赖关键词：

- 标题命中
- 关键词命中
- 正文命中
- 查询改写
- 简单 rerank

这套方法优点是可解释、好调试，但也有局限：

- 用户表达和知识库关键词不完全一致时，可能召回不足。
- 中文短句没有天然分词，纯关键词容易漏掉口语表达。
- 面试中容易被追问“你有没有考虑向量召回和 rerank？”

所以这一版新增了轻量本地向量分数。它不是生产级 embedding 模型，而是一个可本地运行、可测试、可解释的向量召回 baseline。

## 2. 当前实现方式

核心位置：

```text
KnowledgeTools
KnowledgeSearchResult
ToolRegistry knowledge_search outputSchema
```

当前做法：

```text
用户问题
  -> 查询改写
  -> 抽取检索词
  -> 关键词打分
  -> 字符 n-gram 伪向量相似度
  -> vectorBoost 加入总分
  -> rerank 排序
```

## 3. 本地轻量向量

真实生产里通常会用 embedding 模型把文本转成向量，再放入向量数据库。

当前项目为了保持本地可运行、测试稳定，使用字符 n-gram 构造伪向量：

- 1 字符特征：提高短词召回。
- 2 字符特征：让中文短语更有区分度。
- 余弦相似度：计算 query 和 document 的相似程度。

简化理解：

```text
"耳机坏了能修吗"
  -> ["耳", "机", "坏", "了", "能", "修", "吗", "耳机", "机坏", ...]
```

文档标题、关键词和正文片段也会被转成类似特征，然后计算相似度。

## 4. 打分融合

当前分数由几部分组成：

```text
totalScore =
  titleScore
  + keywordScore
  + contentScore
  + vectorBoost
  + fieldCoverageBonus
```

其中：

- `titleScore`：标题命中，权重最高。
- `keywordScore`：人工关键词命中。
- `contentScore`：正文片段命中。
- `vectorBoost`：本地向量相似度超过阈值后的补充分。
- `fieldCoverageBonus`：命中字段越多，说明结果越可靠。

`KnowledgeSearchResult` 新增：

```text
vectorScore
```

Prompt 文本里会展示“向量分”，方便调试和面试解释。

## 5. 为什么不直接上向量数据库

当前阶段暂不引入 Milvus、pgvector、Elasticsearch dense vector，原因是：

- 项目知识库规模还小，Markdown 文件足够支撑演示。
- 外部向量数据库会增加启动和部署成本。
- CI 和本地测试不应该依赖外部 embedding 服务。
- 对新手来说，先理解 RAG 的召回、排序、缺口记录，比堆中间件更重要。

当前方案可以作为面试中的渐进路线：

```text
关键词 baseline
  -> 查询改写
  -> 本地轻量向量分
  -> 后续真实 embedding
  -> 向量数据库
  -> LLM rerank / cross-encoder rerank
```

## 6. 验证方式

已补充测试：

```text
KnowledgeToolsTest.shouldExposeLocalVectorScoreForRagSearch
```

运行：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" "-Dtest=KnowledgeToolsTest" test
```

测试重点：

- RAG 检索结果中能看到 `vectorScore`。
- 检索结果仍然保持原有关键词和 rerank 能力。
- `knowledge_search` 工具 outputSchema 已声明 `vector_score`。

## 7. 面试怎么讲

可以这样讲：

> 我先做了可解释的关键词检索和查询改写，保证政策问答不会完全依赖大模型猜。之后补了轻量本地向量召回，用中文字符 n-gram 构造伪向量，再用余弦相似度作为排序补充信号。这样不依赖外部 embedding 服务，也能展示 RAG 从关键词召回向向量召回演进的思路。

如果被追问生产怎么升级：

> 生产里我会把当前 `calculateVectorScore` 替换成真实 embedding 模型，把文档切块后写入 pgvector、Milvus 或 Elasticsearch，并在召回后增加 rerank。当前代码已经把 `vectorScore` 和 `rerankReason` 放进结构化结果里，后续替换底层召回方式时，上层 Agent 和评测逻辑可以基本复用。

