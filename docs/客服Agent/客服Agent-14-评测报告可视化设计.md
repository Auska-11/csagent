# 客服Agent-14-评测报告可视化设计

## 1. 为什么要做评测报告可视化

客服 Agent 项目如果只展示聊天效果，很容易变成普通 Demo。

真正有工程深度的 Agent 项目，需要回答三个问题：

- Agent 能不能完成任务？
- Agent 为什么这样执行？
- 我们怎么证明这次修改没有让效果变差？

前面的 Agent Trace 解决的是“为什么这样执行”。
本阶段的评测报告可视化解决的是“怎么证明效果没有变差”。

## 2. 当前评测报告来源

当前项目已经有四类离线评测：

| 报告 | 关注点 |
| --- | --- |
| `customer-service-routing.json` | 意图识别、Action 选择、工具路由、人工转接 |
| `knowledge-recall.json` | 知识库文档召回和关键词覆盖 |
| `response-quality.json` | 回复是否包含必要信息、是否避开禁止表达 |
| `agent-response-chain.json` | 端到端回复质量和 Trace 链路覆盖 |

这些报告由测试生成，统一输出到：

```text
target/eval-reports/
```

其中：

- `summary.json`：最新一次评测聚合结果。
- `trend.json`：历史快照和指标变化趋势。
- `history/`：每次评测的历史快照。

## 3. 后端接口设计

新增只读接口：

```http
GET /api/eval/reports
```

返回结构示例：

```json
{
  "available": true,
  "reportDir": "target/eval-reports",
  "summary": {
    "generatedAt": "2026-07-06T16:09:11Z",
    "reportCount": 4,
    "reports": []
  },
  "trend": {
    "generatedAt": "2026-07-06T16:09:12Z",
    "reportCount": 4,
    "reports": []
  }
}
```

### 3.1 为什么只读固定文件

评测报告接口只读取固定目录下的 `summary.json` 和 `trend.json`。

不让前端传文件名或路径，是为了避免把这个接口做成任意文件读取入口。

### 3.2 为什么报告不存在也返回 200

新同学第一次启动项目时，可能还没有运行过测试，因此 `target/eval-reports` 不存在。

这时接口返回：

```json
{
  "available": false,
  "summary": {
    "reportCount": 0,
    "reports": []
  }
}
```

这样前端可以正常展示空状态，而不是报错。

## 4. 前端 Eval 面板

Agent Inspector 新增 `Eval` Tab。

当前展示：

- Available：当前是否已有评测报告。
- Reports：报告数量。
- Cases：评测用例总数。
- Avg Score：各报告核心指标的平均值。
- Reports 列表：每个报告的核心指标和 summary JSON。
- Trend 列表：每个报告的历史运行次数、最新核心指标和指标变化。
- 支持复制完整评测报告 JSON。

这个面板的定位不是替代专业监控平台，而是做一个轻量的 Agent 质量看板。

## 5. 核心指标选择策略

不同报告的指标名称不同，例如：

- `intentAccuracy`
- `documentRecall`
- `qualityPassRate`
- `traceCoverageRate`
- `expectationMatchRate`

前端会从每个报告的 summary 中选一个最能代表该报告质量的核心指标。

这样做的好处是：

- 面板可以快速展示每类评测是否健康。
- 不需要为每个报告单独写一套 UI。
- 后续新增评测报告时，只要 summary 中有 `Rate`、`Accuracy`、`Recall`、`Coverage` 这类指标，就能被自动识别。

## 6. 面试表达

可以这样讲：

> 我没有只做一个能聊天的客服 Agent，而是补了评测闭环。测试会生成 routing、knowledge recall、response quality 和 agent response chain 四类 JSON 报告，后端提供只读评测报告接口，前端 Agent Inspector 的 Eval 面板会展示最新指标和趋势变化。这样每次改意图识别、工具调用或回复生成后，都可以通过评测报告判断有没有回归。

## 7. 后续优化

后续可以继续增强：

- 增加单个 case 的失败详情展示。
- 在 Eval 面板中标红下降的指标。
- 接入 CI，让每次提交自动生成评测报告。
- 增加 LLM-as-Judge 报告，用模型从准确性、完整性、客服语气和安全性四个维度打分。
- 把历史趋势画成折线图。

