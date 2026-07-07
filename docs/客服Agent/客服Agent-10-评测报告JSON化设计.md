# 智能客服 Agent：评测报告 JSON 化设计

> 本文档记录评测系统从“只打印控制台报告”升级为“同时输出 JSON 指标报告”的设计。

## 1. 为什么要把评测报告 JSON 化

早期评测只在控制台打印结果：

```text
路由评测：
总用例数：16
意图命中率：100.0% (16/16)
```

这样适合人工查看，但不适合长期维护。

如果后续项目继续变复杂，我们会想回答这些问题：

- 这次修改后，路由准确率有没有下降？
- 知识库召回是不是比上一次更好？
- 哪些 case 失败了？
- Agent 链路里 Trace 事件有没有缺失？
- 能不能自动生成评测报告，甚至接一个简单 Monitor 页面？

这些都需要结构化数据，而不是只靠控制台文本。

## 2. 当前输出位置

运行测试：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" test
```

会生成：

```text
target/eval-reports/
```

当前包含聚合入口、趋势入口、历史快照目录和四个明细 JSON 文件：

| 文件 | 对应评测 | 说明 |
| --- | --- | --- |
| `summary.json` | 聚合入口 | 汇总当前已有评测报告和总体指标 |
| `trend.json` | 趋势入口 | 汇总每类评测的最新指标、上一次指标和数值变化量 |
| `history/` | 历史快照 | 按评测类型保存每次运行生成的历史报告 |
| `customer-service-routing.json` | 路由评测 | 意图、Action、工具、转人工命中情况 |
| `knowledge-recall.json` | 知识库召回评测 | 文档召回、关键词覆盖、topK 结果 |
| `response-quality.json` | 回复质量规则评测 | 正例通过、负例拦截、缺失关键词 |
| `agent-response-chain.json` | Agent 链路回复评测 | 最终回复、Trace 事件覆盖、质量规则结果 |

`target` 是 Maven 构建输出目录，所以这些报告不需要提交到 Git。

## 3. JSON 报告结构

每个报告都有相同的基础结构：

```json
{
  "reportName": "customer-service-routing",
  "generatedAt": "2026-07-06T14:16:03Z",
  "summary": {
    "total": 16,
    "intentAccuracy": 1.0
  },
  "cases": [
    {
      "id": "order_query_with_order_id",
      "passed": true
    }
  ]
}
```

字段说明：

- `reportName`：报告名称。
- `generatedAt`：生成时间。
- `summary`：总体指标。
- `cases`：每条评测样本的详细结果。

## 4. 代码设计

当前新增了测试侧工具类：

```text
src/test/java/com/smartcs/smart_cs_agent/eval/EvalReportWriter.java
```

它主要负责五件事：

1. 创建 `target/eval-reports` 目录。
2. 组装统一报告结构。
3. 使用 Jackson 写出格式化 JSON。
4. 刷新 `summary.json` 聚合入口。
5. 保存 `history/` 历史快照，并刷新 `trend.json` 趋势入口。

之所以放在 `src/test/java`，是因为当前 JSON 报告属于离线评测产物，不属于线上业务接口。

## 5. 五类评测如何写入 JSON

当前五个 Runner 都会调用 `EvalReportWriter`：

- `CustomerServiceEvaluationTest`
- `KnowledgeRecallEvaluationTest`
- `ResponseQualityEvaluationTest`
- `AgentResponseEvaluationTest`
- `LlmAsJudgeEvaluationTest`

每个 Runner 都保留原来的控制台输出，同时新增 JSON 输出。

这样做的好处是：

- 本地开发时仍然能直接看控制台。
- 自动化脚本或后续页面可以读取 JSON。
- 不破坏原有测试断言逻辑。

## 5.1 趋势报告如何工作

每次 Runner 调用 `EvalReportWriter.write(...)` 时，会同时做三类写入：

1. 覆盖最新明细报告，例如 `customer-service-routing.json`。
2. 在 `history/<reportName>/` 下保存一份带时间戳的历史快照。
3. 重新生成 `trend.json`。

`trend.json` 中每个报告会包含：

- `latestSummary`：最新一次指标。
- `previousSummary`：上一次指标。
- `metricDelta`：数值型指标的变化量。
- `recentRuns`：最近若干次历史快照。

如果后续接入 Monitor 页面，可以直接读取 `trend.json` 展示指标走势；如果接入 CI，也可以用 `metricDelta` 判断本次提交是否造成评测退化。

## 6. 面试表达

如果面试官问：“你怎么持续评估 Agent 效果？”

可以这样回答：

> 我没有只靠手动 Demo，而是做了离线评测体系。当前评测覆盖路由、知识库召回、回复质量规则和 Agent 链路回复四类场景。每次运行 mvn test 时，除了控制台报告，还会在 target/eval-reports 下输出 JSON 指标报告、历史快照和 trend.json 趋势入口。这样后续可以接入简单的 Monitor 页面，看意图准确率、工具命中率、知识库召回率和 Trace 覆盖率是否退化。

如果面试官问：“为什么 JSON 报告不提交到 Git？”

可以这样回答：

> JSON 报告是测试运行产物，和 target 下的 class 文件类似，不应该作为源码提交。真正应该提交的是评测用例、评测 Runner 和报告生成逻辑。这样任何人拉取项目后运行测试，都可以重新生成同样格式的报告。

## 7. 后续可以继续增强什么

下一步可以继续做：

1. 把 JSON 报告渲染成 Markdown 或 HTML。
2. 接入真实模型输出评测，把真实回复也写入 `cases`。
3. 在 CI 中保存报告产物，用来观察项目质量变化。
4. 增加失败 case 的自动摘要，方便快速定位退化原因。

当前阶段已经做到“指标可落盘、case 可追溯、总览可读取、历史可比较”，已经足够支撑简历项目的工程深度。
