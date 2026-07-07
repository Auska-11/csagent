# 智能客服 Agent：知识库 RAG 设计

## 1. 为什么要做知识库 RAG

客服 Agent 里有两类问题要分开处理：

- 业务数据类：订单状态、退货进度、用户信息，需要调用业务工具。
- 政策规则类：退货政策、保修政策、发票政策、配送规则，需要查知识库。

如果政策问题直接交给大模型自由回答，会有一个明显风险：

> 模型可能把不存在的政策说得很像真的。

例如用户问：

```text
超过七天还能退吗？
发票什么时候能开？
偏远地区可以配送吗？
保修期多久？
```

这些问题必须有明确依据。RAG 的作用就是先检索知识库，再让模型基于检索结果组织客服话术。

本项目第一版不直接上向量数据库，而是先做 Markdown 知识库 + 可解释检索增强。这样更适合当前阶段：

- 代码简单，容易读懂。
- 检索结果可解释。
- Trace 里能看到命中了哪篇文档，以及没有命中时的 `knowledge_miss`。
- 后续可以逐步升级为 embedding 检索和 LLM rerank。

## 2. 当前实现范围

本阶段已完成第一版知识库能力：

- 新增 Markdown 知识库目录：

```text
src/main/resources/knowledge/
├── policy/
│   ├── return_policy.md
│   ├── warranty_policy.md
│   └── invoice_policy.md
├── logistics/
│   └── delivery_policy.md
└── service/
    └── handoff_policy.md
```

- 新增知识库模型：
  - `KnowledgeDocument`
  - `KnowledgeSearchResult`
- 新增知识库工具：
  - `KnowledgeTools`
- 新增工具能力：
  - `knowledge_search`
- RAG 检索增强第一版：
  - 查询改写：把“坏了能修吗”“几天能送到”等口语表达扩展成保修、维修、配送、物流等检索词。
  - 多路召回：标题、人工关键词、正文分别打分。
  - 简单 rerank：按分数和命中字段覆盖度排序。
  - 召回失败 Trace：没有可靠结果时记录 `knowledge_miss`。
- `CustomerServiceSkill` 支持政策类问题路由到知识库。
- `ToolRegistryTest`、`KnowledgeToolsTest`、`CustomerServiceSkillTest`、`KnowledgeRecallEvaluationTest` 覆盖知识库链路。

## 3. 核心类说明

## 3.1 `KnowledgeDocument`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/tools/KnowledgeDocument.java
```

作用：表示一篇被加载进内存的知识库文档。

字段：

```java
private String id;
private String category;
private String title;
private String content;
private List<String> keywords;
```

为什么要有 `keywords`？

中文没有天然空格分词，第一版又没有引入分词器和向量模型，所以人工关键词能显著提升召回稳定性。

例如 `return_policy.md` 里有：

```text
关键词：退货, 换货, 七天无理由, 超过七天, 运费, 质量问题, 退款
```

这样用户问“超过七天还能退吗”时，即使正文表达不是完全相同，也能命中退换货政策。

## 3.2 `KnowledgeSearchResult`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/tools/KnowledgeSearchResult.java
```

作用：表示一次检索命中的结果。

字段：

```java
private String documentId;
private String category;
private String title;
private int score;
private String snippet;
private List<String> matchedTerms;
private List<String> matchedFields;
private List<String> queryVariants;
private String rerankReason;
```

它不是完整文档，而是“某次查询下的命中结果”。

`score` 用于排序，`snippet` 用于交给 LLM 或 Trace 展示。

`matchedTerms`、`matchedFields`、`queryVariants`、`rerankReason` 用于解释检索过程。
这几个字段让我们能回答一个很关键的问题：这条知识为什么被召回、为什么排在前面。

## 3.3 `KnowledgeTools`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/tools/KnowledgeTools.java
```

职责：

- 从 `classpath:/knowledge/**/*.md` 加载 Markdown 文档。
- 解析标题和关键词。
- 对用户问题做查询改写和关键词扩展。
- 按标题、关键词、正文三路召回并打分。
- 对候选结果做简单 rerank。
- 返回结构化命中结果。
- 格式化成适合 LLM 使用的检索文本。

核心方法：

```java
public List<KnowledgeSearchResult> search(String query, int topK)

public String searchKnowledge(String query)

public String formatSearchResult(String query, List<KnowledgeSearchResult> results)
```

## 4. 检索策略

当前采用可解释的轻量 RAG 检索。

### 4.1 查询改写

用户不会总是直接说知识库里的标准词。

例如：

```text
耳机坏了能修吗 -> 保修、质保、维修、售后检测、质量问题
一般几天能送到 -> 配送、物流、发货、时效、签收
专票要填什么税号 -> 发票、专用发票、增值税专用发票、抬头、税号
```

第一版先用规则改写，把口语表达扩展成更适合检索的候选关键词。

### 4.2 多路召回

每篇文档会从三个位置匹配：

| 匹配位置 | 权重 | 原因 |
| --- | --- | --- |
| 标题 | 高 | 标题通常代表文档主题 |
| 关键词 | 高 | 人工维护，适合修正中文表达差异 |
| 正文 | 中 | 能补充标题和关键词没有覆盖的细节 |

当前打分逻辑可以理解为：

```text
标题命中：+12
关键词命中：+10
正文命中：+4
命中字段覆盖加分：每个字段 +2
低于阈值：认为没有可靠命中
```

### 4.3 简单 rerank

候选结果先按总分排序。
如果分数相同，再看命中的字段数量。

原因是：一篇文档如果同时命中标题、关键词和正文，通常比只在正文里碰巧出现一个词更可靠。

这个方案不是最终形态，但有两个优点：

- 容易解释。
- 容易测试。

面试里可以把它讲成：

> 我先实现了一个可解释的 sparse retrieval baseline，用标题、人工关键词和正文命中打分。后续如果要提升召回，可以在这个基础上加入 embedding 检索、查询改写和 LLM rerank。
> 后续我又补了查询改写、多路召回和简单 rerank，让“耳机坏了能修吗”这类口语问题也能召回保修政策，同时在结果里保留 matchedTerms、matchedFields 和 rerankReason，方便调试和面试解释。

## 5. 工具调用链路

知识库检索被注册成标准工具 `knowledge_search`。

调用链路：

```text
用户政策问题
  ↓
CustomerServiceSkill 识别为 knowledge_query
  ↓
CapabilityAction("knowledge_search", {"query": 用户问题})
  ↓
AgentRuntime
  ↓
ToolManager
  ↓
ToolRegistry
  ↓
KnowledgeTools.search(...)
  ↓
ToolResult(success, message, data)
  ↓
LLMAction(response_generation)
  ↓
FinalAnswerAction
```

这说明知识库不是绕开工具层单独调用，而是和订单、商品、退货工具一样，统一走 MCP-style 工具边界。

## 6. Skill 路由规则

`CustomerServiceSkill` 中新增了政策问题判断：

```java
private boolean isKnowledgeRelatedMessage(String lowerMessage)
```

它会识别：

- 退换货政策：`超过七天`、`七天无理由`、`退货政策`、`退货运费`
- 发票政策：`发票`、`电子发票`、`专用发票`、`抬头`、`税号`
- 保修政策：`保修`、`质保`、`维修政策`
- 配送政策：`偏远地区`、`配送范围`、`发货时效`
- 转人工规则：`人工客服`、`转人工`、`投诉规则`

特别注意：

```text
“我要退货” -> 退货申请任务
“超过七天还能退吗？” -> 知识库政策问题
```

两者都包含“退”，但业务含义不同，所以知识库路由放在退货申请规则前面。

## 7. Prompt 约束

`KnowledgeTools.formatSearchResult(...)` 会在给 LLM 的文本里加入约束：

```text
请只基于以下内容回答，不要编造未出现的政策。
```

这条很重要。

RAG 不是“把资料塞给模型就完事”，还要明确告诉模型：

- 只能基于检索内容回答。
- 找不到就说明没有找到。
- 不要补充知识库里没有的承诺。

## 8. 测试设计

当前新增和更新的测试：

```text
src/test/java/com/smartcs/smart_cs_agent/tools/KnowledgeToolsTest.java
src/test/java/com/smartcs/smart_cs_agent/tools/ToolRegistryTest.java
src/test/java/com/smartcs/smart_cs_agent/skill/CustomerServiceSkillTest.java
```

覆盖内容：

- `超过七天还能退吗` 能命中退换货政策。
- 给 LLM 的检索文本包含“不要编造”约束。
- 不相关问题不会返回不可靠结果。
- “耳机坏了能修吗”能通过查询改写命中保修政策。
- 检索结果会暴露命中词、命中字段和 rerank 原因。
- 知识库没有命中时，Runtime 会记录 `knowledge_miss` Trace。
- `ToolRegistry` 可以执行 `knowledge_search`。
- `CustomerServiceSkill` 会把政策问题路由到 `knowledge_search`。
- `KnowledgeRecallEvaluationTest` 覆盖 8 条召回评测样本，当前文档召回率和关键词覆盖率均为 100%。

这能证明知识库链路不是只写了代码，而是有可验证行为。

## 9. 当前不足

第一版知识库检索仍然有明显限制：

- 没有真正语义检索。
- 中文分词仍然比较朴素。
- 查询改写目前是规则驱动，不是模型生成。
- rerank 目前是规则重排，还不是 LLM rerank。
- Markdown 文档内容仍然比较少。

这些不足并不是问题，反而可以作为后续优化路线。

## 10. 后续优化方向

建议后续按这个顺序升级：

1. 扩充知识库文档：
   - 退货细则
   - 发票异常
   - 物流异常
   - 售后检测
   - 人工转接边界

2. 混合检索：
   - 关键词检索
   - embedding 检索
   - 合并召回结果

3. 更强 rerank：
   - 第一版可以用规则重排
   - 后续可以用 LLM 判断哪条结果最相关

4. 召回失败闭环：
   - 统计 `knowledge_miss`
   - 把常见 miss 问题沉淀为新知识文档或新查询改写规则

## 11. 面试表达

如果面试官问：“政策类问题怎么避免模型胡说？”

可以这样回答：

> 我没有让模型直接回答政策类问题，而是把退换货、保修、发票、配送等规则沉淀成 Markdown 知识库。用户提出政策问题时，Skill 会路由到 knowledge_search 工具，先检索相关文档，再把检索片段交给 LLM 生成客服回复。Prompt 里会明确要求只基于检索内容回答，找不到就说明未找到并建议补充信息或转人工。

如果面试官问：“为什么不用向量数据库？”

可以这样回答：

> 当前知识库规模不大，所以我先做了可解释的检索 baseline，用标题、人工关键词和正文命中打分。后面又补了规则查询改写、多路召回和简单 rerank，让口语化问题也能命中正确文档。这样更容易测试和调试，也能通过 Trace 和 matchedTerms、matchedFields 解释为什么命中。后续如果知识库规模变大，再引入 embedding 检索和 LLM rerank。

如果面试官问：“你的 RAG 怎么评测？”

可以这样回答：

> 我把评测拆成两层：第一层评测召回，检查用户问题是否命中期望文档，以及检索文本是否覆盖关键政策点；第二层评测最终回答，检查是否忠实于知识库、是否没有编造承诺。当前 KnowledgeRecallEvaluationTest 已覆盖 8 条样本，包括“耳机坏了能修吗”“一般几天能送到”这类口语表达。

## 12. 当前结论

知识库 RAG 第一版和检索增强第一版已经完成。

当前项目已经支持：

- Markdown 知识库
- 本地可解释检索
- 查询改写
- 多路召回
- 简单 rerank
- `knowledge_search` 标准工具
- `knowledge_miss` Trace
- 政策类问题路由
- 工具结果进入 Trace
- 单元测试和召回评测覆盖核心链路

这一步完成后，客服 Agent 不再只会查业务数据，也能基于可追溯知识回答政策类问题。
