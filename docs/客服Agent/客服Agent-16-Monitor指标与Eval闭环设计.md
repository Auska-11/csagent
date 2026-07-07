# 客服Agent-16-Monitor指标与Eval闭环设计

## 1. 为什么要做 Monitor

客服 Agent 不能只看“有没有回复用户”，还要能回答这些问题：

- 工具调用是否稳定？
- LLM 是否经常失败？
- 人工转接是否过多？
- 每轮对话的耗时大概是多少？
- 最近几轮 Agent 表现是否正常？

这些问题就是 Monitor 要解决的。

Eval 偏离线评测，回答“版本质量怎么样”；
Monitor 偏在线观察，回答“系统运行时表现怎么样”。

## 2. 第一版边界

第一版不接 Prometheus，也不接数据库。

当前实现是一个轻量内存版 Monitor：

- 每轮聊天结束后，从 `ChatResponse.trace` 读取本轮事件。
- 把 Trace 事件聚合成工具、LLM、转人工和耗时指标。
- 通过接口返回当前 JVM 内存中的摘要。
- 前端 Agent Inspector 增加 Monitor Tab 展示指标。

这样做的好处是改动小、容易讲清楚，也能复用已经完成的 Agent Trace。

## 3. 核心代码

### 3.1 指标服务

```text
src/main/java/com/smartcs/smart_cs_agent/monitor/MonitorService.java
```

职责：

- 消费 `ChatResponse`。
- 遍历 `TraceEvent`。
- 统计工具调用次数、成功率、LLM 失败率、转人工次数和平均耗时。
- 保留最近 10 轮对话摘要。

### 3.2 指标 DTO

```text
src/main/java/com/smartcs/smart_cs_agent/monitor/MonitorSummary.java
src/main/java/com/smartcs/smart_cs_agent/monitor/MonitorTurnSnapshot.java
```

`MonitorSummary` 是全局聚合结果。

`MonitorTurnSnapshot` 是最近单轮对话摘要。

### 3.3 指标接口

```text
src/main/java/com/smartcs/smart_cs_agent/controller/MonitorController.java
```

接口：

```http
GET /api/monitor/summary
```

### 3.4 聊天链路接入

```text
src/main/java/com/smartcs/smart_cs_agent/controller/ChatApiController.java
```

每次聊天成功后执行：

```java
monitorService.recordTurn(response);
```

注意：Monitor 不参与 Agent 决策，只观察结果。
这样不会影响 `AgentRuntime` 的主执行链路。

## 4. 当前统计的 Trace 事件

| Trace 事件 | Monitor 含义 |
| --- | --- |
| `tool_observed` | 一次普通工具调用完成 |
| `tool_failed` | 一次普通工具调用异常 |
| `handoff_requested` | 本轮触发人工转接 |
| `handoff_created` | 人工工单创建成功，也算一次工具成功 |
| `handoff_failed` | 人工工单创建失败，也算一次工具失败 |
| `llm_called` | 一次 LLM 调用开始 |
| `llm_failed` | 一次 LLM 调用失败 |
| `llm_observed` | 一次 LLM 调用或降级结果返回 |

## 5. 当前指标

`GET /api/monitor/summary` 返回：

- `totalTurns`：累计聊天轮数。
- `toolCallCount`：累计工具调用次数。
- `successfulToolCallCount`：累计成功工具调用次数。
- `failedToolCallCount`：累计失败工具调用次数。
- `toolSuccessRate`：工具成功率。
- `llmCallCount`：累计 LLM 调用次数。
- `llmFailureCount`：累计 LLM 失败次数。
- `llmFailureRate`：LLM 失败率。
- `handoffCount`：累计转人工次数。
- `averageToolDurationMs`：平均工具耗时。
- `averageLlmDurationMs`：平均 LLM 耗时。
- `recentTurns`：最近 10 轮对话摘要。

## 6. 和 Eval 的关系

Monitor 和 Eval 不是重复功能。

Eval：

- 离线运行。
- 基于固定测试集。
- 更适合判断“版本质量”。

Monitor：

- 在线统计。
- 基于真实请求产生的 Trace。
- 更适合观察“运行表现”。

后续可以把两者合起来：

- Eval 发现版本质量下降时，阻止合并或发布。
- Monitor 发现线上 LLM 失败率升高时，触发降级策略或报警。
- Monitor 指标长期积累后，可以反过来补充 Eval 测试样本。

## 7. 测试设计

新增测试：

```text
src/test/java/com/smartcs/smart_cs_agent/monitor/MonitorServiceTest.java
src/test/java/com/smartcs/smart_cs_agent/controller/MonitorControllerTest.java
```

覆盖：

- 能从 Trace 聚合工具成功率。
- 能统计 LLM 失败率。
- 能统计转人工次数。
- 最近对话摘要只保留 10 条。
- Controller 能返回 MonitorSummary。

## 8. 面试表达

如果面试官问：“你这个项目怎么监控 Agent 表现？”

可以这样回答：

> 我没有一开始就接复杂的监控系统，而是先把 Agent 每一步执行过程记录成 Trace，包括工具调用、LLM 调用、人工转接和耗时。然后做了一层 MonitorService，它不参与 Agent 决策，只消费每轮 ChatResponse 里的 Trace，聚合出工具成功率、LLM 失败率、转人工次数和平均耗时。这样 Trace 是事实日志，Monitor 是统计视图。后续如果要接 Prometheus 或数据库，只需要替换 MonitorService 的存储实现，接口和前端可以保持稳定。

## 9. 后续扩展

- 按工具名称统计成功率，例如 `order_lookup`、`knowledge_search` 分开看。
- 按意图统计转人工率。
- 记录窗口指标，例如最近 5 分钟 LLM 失败率。
- 指标超过阈值时触发告警或自动降级。
- 将 Monitor 指标和 Eval 报告结合，形成“离线质量 + 在线表现”的闭环。
