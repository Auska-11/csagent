# 智能客服 Agent：端到端评测设计

## 1. 为什么需要评测

客服 Agent 如果只靠手动测试，很容易出现两个问题：

- 看起来能聊，但不知道整体准确率。
- 改了一处规则，另一类场景悄悄坏掉。

所以项目需要评测集。

评测的价值是：

- 用数据证明 Agent 能力。
- 保护后续重构不破坏核心场景。
- 面试时能展示工程化闭环，而不是只展示 Demo。

## 2. 当前评测体系做什么

当前评测体系先做确定性强、可重复执行的离线评测，不依赖真实 LLM 的随机输出。

目前已经包含五类评测：

| 评测类型 | 评测目标 | 当前状态 |
| --- | --- | --- |
| 路由评测 | 验证意图、Action、工具和转人工判断是否正确 | 已完成第一版 |
| 知识库召回评测 | 验证 `knowledge_search` 是否能找到正确政策文档和关键政策点 | 已完成第一版 |
| 回复质量规则评测 | 验证候选回复是否覆盖必要关键词、是否出现危险承诺 | 已完成第一版 |
| Agent 链路回复评测 | 真实跑过 Agent / Skill / Runtime / Tool，再评估最终回复和 Trace | 已完成第一版 |
| LLM-as-Judge 评测 | 从准确性、完整性、客服语气、安全性四个维度评估回复 | 已完成第一版 |

为什么先不直接依赖真实 LLM 做完整端到端评测？

因为最终回复依赖 LLM，结果会有随机性，也可能受网络、模型版本和配置影响。

我们先把确定性链路评稳：

```text
用户输入
  ↓
Skill 决策
  ↓
Action
  ↓
工具 / 知识库 / 转人工 / 追问
  ↓
规则评测验证关键业务点
```

这样做的好处是：每次修改规则、工具或知识库后，都可以稳定地跑回归测试。

后续再升级：

- 真实模型回复评测
- 知识库忠实性评测
- 真实模型版本的 LLM-as-Judge 四维度评分

## 2.1 路由评测

评测对象是 `CustomerServiceSkill` 的决策结果：

- 意图是否正确。
- Action 类型是否正确。
- 工具是否命中。
- 是否应该转人工。
- 多轮任务最后是否能走到正确工具。

## 2.2 知识库召回评测

路由评测只能说明“政策类问题会走 `knowledge_search`”，但不能证明知识库真的找到了正确内容。

所以项目新增了 `KnowledgeRecallEvaluationTest`，直接测试 `KnowledgeTools.search(...)` 的召回效果：

- topK 结果是否包含期望文档。
- 格式化后的检索文本是否包含关键政策点。
- 特殊问题是否能召回足够完整的片段，例如企业发票需要公司抬头和纳税人识别号。

## 2.3 回复质量规则评测

回复质量评测第一版不使用 LLM-as-Judge，而是使用规则：

- `requiredKeywords`：回复必须覆盖的关键点。
- `forbiddenKeywords`：回复不能出现的危险承诺、内部工具名或不该暴露的信息。

这套评测当前先验证静态候选回复，后续可以把真实 Agent 回复保存成 `candidateReply`，继续复用同一套规则。

它的价值是先把“客服回复不能乱承诺、不能漏关键信息”这件事变成可测试规则。

后续可以继续升级：

- 调用真实 Agent 得到 candidateReply。
- 让评测用例保存工具结果和知识库检索结果。
- 继续扩展真实模型版本的 LLM-as-Judge，判断自然度、忠实性和客服语气。

## 2.4 Agent 链路回复评测

静态回复质量评测只能说明“某段候选回复是否合格”，但不能证明这段回复来自真实 Agent 链路。

所以项目新增了 `AgentResponseEvaluationTest`：

- 按 `turns` 多轮调用 `CustomerServiceAgent.chatWithTrace(...)`。
- 请求真实经过 `ConversationStore`、`CustomerServiceSkill`、`AgentRuntime` 和工具层。
- 使用 Fake LLM 返回稳定回复，避免真实模型随机性和网络依赖。
- 最终检查回复关键词、危险承诺和 Trace 事件覆盖。

它比静态回复评测更接近端到端，但仍保持测试稳定。

## 3. 当前文件

路由评测用例：

```text
src/test/resources/eval/customer_service_eval_cases.json
```

路由评测 Runner：

```text
src/test/java/com/smartcs/smart_cs_agent/eval/CustomerServiceEvaluationTest.java
```

知识库召回评测：

```text
src/test/resources/eval/knowledge_recall_eval_cases.json
src/test/java/com/smartcs/smart_cs_agent/eval/KnowledgeRecallEvaluationTest.java
```

回复质量规则评测：

```text
src/test/resources/eval/response_quality_eval_cases.json
src/test/java/com/smartcs/smart_cs_agent/eval/ResponseQualityEvaluationTest.java
```

Agent 链路回复评测：

```text
src/test/resources/eval/agent_response_eval_cases.json
src/test/java/com/smartcs/smart_cs_agent/eval/AgentResponseEvaluationTest.java
```

JSON 报告输出目录：

```text
target/eval-reports/
```

当前输出文件：

```text
summary.json
trend.json
history/
customer-service-routing.json
knowledge-recall.json
response-quality.json
agent-response-chain.json
```

## 4. 评测用例结构

每条 case 的结构如下：

```json
{
  "id": "return_apply_multi_turn",
  "turns": ["我要退货", "ORD-004", "质量问题"],
  "expectedIntent": "return_apply",
  "expectedAction": "CapabilityAction",
  "expectedCapability": "return_apply",
  "expectedHandoff": false
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `id` | 用例唯一标识 |
| `turns` | 用户多轮输入 |
| `expectedIntent` | 期望最终意图 |
| `expectedAction` | 期望最终 Action 类型 |
| `expectedCapability` | 期望工具名称 |
| `expectedHandoff` | 是否应该转人工 |

## 5. 当前覆盖场景

当前评测集包含 16 条 case，覆盖：

- 订单查询：
  - 已提供订单号
  - 缺少订单号追问
  - 查询用户全部订单
- 商品咨询：
  - 商品搜索
- 退换货：
  - 查询退货进度
  - 缺少退货单号追问
  - 多轮退货申请
  - 单轮退货申请
- 知识库：
  - 超过七天能否退货
  - 发票开具时间
  - 保修期
  - 偏远地区配送
- 人工转接：
  - 用户主动要求人工
  - 投诉
- 用户信息：
  - 查询我的信息
- 闲聊：
  - 你好

## 6. 当前评测指标

路由评测统计四个指标：

| 指标 | 含义 |
| --- | --- |
| `intentAccuracy` | 意图命中率 |
| `actionAccuracy` | Action 类型命中率 |
| `capabilityAccuracy` | 工具命中率 |
| `handoffAccuracy` | 转人工判断命中率 |

当前测试结果：

```text
路由评测：
总用例数：16
意图命中率：100.0% (16/16)
Action 命中率：100.0% (16/16)
工具命中率：100.0% (11/11)
转人工命中率：100.0% (16/16)

知识库召回评测：
总用例数：6
文档召回率：100.0% (6/6)
关键词覆盖率：100.0% (6/6)

回复质量规则评测：
总用例数：6
判断一致率：100.0% (6/6)
正例通过率：100.0% (4/4)
负例拦截率：100.0% (2/2)

Agent 链路回复评测：
总用例数：3
回复质量通过率：100.0% (3/3)
Trace 事件覆盖率：100.0% (3/3)
```

注意：

这个结果只说明当前评测集全部通过，不代表真实线上场景已经 100% 准确。

面试时应该这样表达：

> 我先构建了一组覆盖核心业务路径的离线评测集，用来验证意图路由、工具选择、多轮槽位、知识库召回、回复质量规则和真实 Agent 链路回复。当前路由 16 条、知识召回 6 条、静态回复质量 6 条、Agent 链路回复 3 条 case 全部通过。后续会继续扩充长尾表达，并加入 LLM-as-Judge。

## 7. Runner 执行逻辑

`CustomerServiceEvaluationTest` 的核心流程：

```text
读取 JSON case
  ↓
按 turns 模拟多轮 ConversationState
  ↓
调用 CustomerServiceSkill.nextAction(...)
  ↓
记录最终 activeIntent、Action、capability、handoff
  ↓
和 expected 字段比较
  ↓
输出控制台报告和 JSON 指标报告
```

第一版只评最终一轮结果。

例如退货多轮：

```text
turns = ["我要退货", "ORD-004", "质量问题"]
```

执行过程：

```text
第 1 轮：AskUserAction，等待 order_id
第 2 轮：AskUserAction，等待 return_reason
第 3 轮：CapabilityAction(return_apply)
```

最终检查第 3 轮是否命中 `return_apply`。

## 8. 评测和单元测试的区别

单元测试关注一个点：

```text
退货流程缺订单号时，是否追问订单号？
```

评测集关注一组业务样本：

```text
当前 Agent 对核心客服问题的整体命中率是多少？
```

两者都需要：

- 单元测试用于定位具体逻辑。
- 评测集用于看整体质量和回归趋势。

## 9. 当前不足

当前评测还不是真正完整线上端到端：

- Agent 链路回复评测已经跑过真实工具和 Runtime，但 LLM 仍然使用 Fake LLM。
- 没有评估最终回复是否自然、准确。
- 知识库召回已经评测，但还没有评估最终回答是否忠实于检索结果。
- 评测用例数量还少。

这些是后续要补的。

## 10. 后续优化方向

建议后续按这个顺序升级：

1. 扩充评测集到 30 到 50 条：
   - 更多中文口语表达
   - 错别字
   - 混合意图
   - 情绪化表达

2. 增强知识库召回评测：
   - 输入问题
   - 期望命中文档
   - topK 是否包含期望文档
   - 片段是否包含关键政策点

3. 增强端到端回复评测：
   - 期望关键词
   - 禁止出现的承诺
   - 是否建议转人工
   - 接入真实 Agent 回复

4. 增加 LLM-as-Judge。已完成第一版：
   - 回答质量
   - 工具调用合理性
   - 知识忠实性
   - 客服语气

5. 持续维护报告文件：
   - Markdown 报告
   - JSON 指标。已完成第一版，输出到 `target/eval-reports/`
   - 历史快照和趋势报告。已完成第一版，输出 `history/` 和 `trend.json`
   - 可放进 README 或面试材料

## 11. 面试表达

如果面试官问：“你怎么证明 Agent 有效果？”

可以这样回答：

> 我没有只靠手动点页面验证，而是做了离线评测集。路由评测会模拟多轮 ConversationState，统计意图、Action、工具和转人工命中率；知识库召回评测会检查 topK 是否命中正确政策文档，以及检索结果是否覆盖关键政策点；回复质量规则评测会检查必要关键词和危险承诺；Agent 链路回复评测会真实跑过 Agent、Runtime 和 Tool，再检查最终回复和 Trace；LLM-as-Judge 会从准确性、完整性、客服语气和安全性四个维度输出结构化评分。当前五类评测全部通过，并且每次运行测试都会在 target/eval-reports 下生成 JSON 指标报告，方便后续做趋势对比。

如果面试官问：“为什么这还不算完整端到端评测？”

可以这样回答：

> 因为当前评测主要验证确定性链路和规则质量，Agent 链路评测虽然会跑过 Runtime 和 Tool，但 LLM 仍然使用 Fake LLM；LLM-as-Judge 第一版也使用 Fake Judge LLM，避免测试受网络和模型随机性影响。这样做是为了先把路由、工具、知识召回、基础回复安全性和 Judge 报告链路稳定下来。后续会在此基础上接入真实模型输出和真实模型 Judge。

如果面试官问：“评测集怎么帮助开发？”

可以这样回答：

> 每次我改意图规则、槽位填充或工具注册后，都可以跑这套评测集。如果某条业务路径坏了，测试会直接告诉我 expected 和 actual 的差异，比如期望 `knowledge_search` 但实际走了 `return_apply`。这让 Agent 项目从手动调试变成可回归验证。

## 12. 当前结论

评测体系第一版已经完成，并且已经从“只测路由”升级为“路由 + 知识召回 + 回复质量规则 + Agent 链路回复 + LLM-as-Judge”五类评测。

当前项目已经具备：

- JSON 评测用例。
- 多轮 case 模拟。
- 意图、Action、工具、转人工指标。
- 知识库文档召回和关键词覆盖指标。
- 回复质量必要关键词和危险承诺检查。
- Agent 链路回复质量和 Trace 事件覆盖检查。
- LLM-as-Judge 四维度评分。
- JSON 指标报告输出。
- 历史快照和趋势入口。
- JUnit Runner 自动执行。
- 当前 36 条评测样本全部通过。

这一步完成后，项目已经从“功能能跑”进一步升级为“核心行为和基础回复质量可验证、评测指标可追踪”。
