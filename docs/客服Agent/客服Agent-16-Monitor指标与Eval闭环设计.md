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
- 进一步按工具名统计调用次数、成功率和平均耗时。
- 用户对回复点击有用/无用后，记录为反馈指标。
- 用户点击无用后，自动生成 Eval 待审核候选样本。
- 生成候选样本时，会基于反馈原因和助手回复做第一版问题类型分类。
- 候选样本支持审核为 `APPROVED` 或 `REJECTED`。
- 审核通过的候选样本支持导出为 Eval case 草稿。
- Eval 候选样本会通过 `FeedbackEvalCandidateStore` 保存到本地 JSON 文件，服务重启后还能恢复待审核/已审核状态。
- 人工补齐断言后，`APPROVED` 草稿可以通过合并接口写入正式 `response_quality_eval_cases.json`。
- Monitor Summary 会基于当前指标生成结构化风险告警，例如工具成功率偏低、LLM 失败率偏高、有用反馈率偏低、不安全承诺和内部实现泄露。
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
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackRating.java
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackRecord.java
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackEvalCandidate.java
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackEvalCandidateStatus.java
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackEvalCaseDraft.java
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackEvalCandidateStore.java
src/main/java/com/smartcs/smart_cs_agent/monitor/ResponseQualityEvalCase.java
src/main/java/com/smartcs/smart_cs_agent/monitor/ResponseQualityEvalCaseFileService.java
src/main/java/com/smartcs/smart_cs_agent/monitor/EvalCaseMergeResult.java
src/main/java/com/smartcs/smart_cs_agent/monitor/MonitorAlert.java
src/main/java/com/smartcs/smart_cs_agent/monitor/MonitorAlertSeverity.java
```

`MonitorSummary` 是全局聚合结果。

`MonitorTurnSnapshot` 是最近单轮对话摘要。

`FeedbackRecord` 是最近用户反馈摘要。

`FeedbackEvalCandidate` 是由无用反馈生成的评测候选样本，状态可以是 `PENDING_REVIEW`、`APPROVED` 或 `REJECTED`。

`FeedbackIssueType` 是负反馈问题类型，第一版包括 `MISSING_KEY_INFO`、`UNSAFE_PROMISE`、`POLICY_UNCLEAR`、`TOOL_OR_INTERNAL_LEAK`、`TONE_OR_EXPERIENCE` 和 `OTHER`。

`FeedbackEvalCaseDraft` 是审核通过样本导出的评测草稿，后续可人工整理进正式评测集。

`FeedbackEvalCandidateStore` 是候选样本的本地持久化层，默认写入 `data/feedback-eval-candidates.json`，也可以通过环境变量 `SMARTCS_MONITOR_EVAL_CANDIDATES_FILE` 覆盖路径。

`ResponseQualityEvalCase` 是正式回复质量评测文件的最小字段结构，只包含 `id`、`candidateReply`、`requiredKeywords`、`forbiddenKeywords` 和 `expectedPass`。

`ResponseQualityEvalCaseFileService` 负责把人工补齐断言后的样本合并进 `response_quality_eval_cases.json`，并处理重复 ID 跳过、基础断言校验和文件写入。

`EvalCaseMergeResult` 是合并结果，包含合并前后数量、实际新增数量和被跳过的重复 ID。

`MonitorAlert` 是由指标自动生成的风险告警，包含 `severity`、`code`、`title`、`message` 和 `suggestion`。

`MonitorAlertSeverity` 是告警级别，第一版包括 `INFO`、`WARN` 和 `CRITICAL`。

### 3.3 指标接口

```text
src/main/java/com/smartcs/smart_cs_agent/controller/MonitorController.java
```

接口：

```http
GET /api/monitor/summary
```

```http
POST /api/monitor/feedback
```

```http
GET /api/monitor/feedback/candidates
```

```http
POST /api/monitor/feedback/candidates/{candidateId}/review
```

```http
GET /api/monitor/feedback/candidates/export
```

```http
POST /api/monitor/feedback/candidates/merge
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
- `feedbackCount`：累计反馈数。
- `helpfulFeedbackCount`：有用反馈数。
- `unhelpfulFeedbackCount`：无用反馈数。
- `helpfulFeedbackRate`：有用反馈率。
- `averageToolDurationMs`：平均工具耗时。
- `averageLlmDurationMs`：平均 LLM 耗时。
- `recentTurns`：最近 10 轮对话摘要。
- `toolStats`：按工具名聚合的成功率和耗时。
- `recentFeedbacks`：最近 10 条用户反馈。
- `evalCandidateCount`：累计 Eval 候选样本数。
- `pendingEvalCandidateCount`：当前候选池中待审核样本数。
- `approvedEvalCandidateCount`：当前候选池中已审核通过样本数。
- `rejectedEvalCandidateCount`：当前候选池中已拒绝样本数。
- `evalCandidateIssueCounts`：按负反馈问题类型聚合的候选样本数量。
- `recentEvalCandidates`：最近 10 条待审核候选样本。
  - `status`：候选样本审核状态。
  - `issueType`：候选样本的问题类型。
  - `reviewNote`：审核备注。
  - `reviewedAt`：审核时间。
- `alerts`：由当前指标生成的风险告警列表。
  - `severity`：告警级别。
  - `code`：稳定告警编码，方便测试和排查。
  - `title`：告警标题。
  - `message`：触发原因。
  - `suggestion`：处理建议。

第一版告警阈值先写在 `MonitorService` 中，原因是项目当前还没有真实线上流量和配置中心。固定阈值更容易测试，也更适合面试时讲清楚“先跑通闭环，再逐步工程化”的思路。

Eval 候选样本的持久化路径默认是：

```text
data/feedback-eval-candidates.json
```

如果部署时希望保存到挂载目录，可以配置：

```powershell
$env:SMARTCS_MONITOR_EVAL_CANDIDATES_FILE="D:/smart-cs-agent-data/feedback-eval-candidates.json"
```

这里没有把所有 Monitor 指标都持久化，只持久化 Eval 候选样本。原因是工具成功率、LLM 失败率这类指标更像运行期观测数据，项目重启后可以重新统计；但 Eval 候选样本是需要人工审核和沉淀的质量资产，不能因为服务重启丢失。

正式回复质量评测文件默认写入：

```text
src/test/resources/eval/response_quality_eval_cases.json
```

也可以通过环境变量覆盖：

```powershell
$env:SMARTCS_MONITOR_RESPONSE_QUALITY_EVAL_FILE="D:/smart-cs-agent-data/response_quality_eval_cases.json"
```

合并接口是半自动的，不会把草稿原样写入正式评测集。开发者必须在请求中补齐 `requiredKeywords` / `forbiddenKeywords` 等断言，后端还会校验：正例必须有 `requiredKeywords`，负例必须至少有 `requiredKeywords` 或 `forbiddenKeywords`。这样可以避免“用户觉得无用”这种弱信号直接污染回归测试。

当前告警覆盖：

- 工具调用次数达到 3 次且整体成功率低于 80%。
- LLM 调用次数达到 2 次且失败率高于或等于 50%。
- 用户反馈达到 3 条且有用反馈率低于 60%。
- 负反馈候选样本出现 `UNSAFE_PROMISE`。
- 负反馈候选样本出现 `TOOL_OR_INTERNAL_LEAK`。
- 待审核 Eval 候选样本数量达到 5 条。

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
- 用户无用反馈会先进入 `FeedbackEvalCandidate` 候选池，人工审核后再沉淀为正式 Eval case。
- `APPROVED` 候选样本可以先导出为草稿，再由开发者补齐断言后半自动合并到正式 Eval case，`REJECTED` 样本则保留为已分析但不采纳的质量信号。
- 合并接口不会接受未审核通过的候选样本，也不会接受没有断言的 case，避免把断言不完整的样本混入回归测试。

## 7. 测试设计

新增测试：

```text
src/test/java/com/smartcs/smart_cs_agent/monitor/MonitorServiceTest.java
src/test/java/com/smartcs/smart_cs_agent/controller/MonitorControllerTest.java
```

覆盖：

- 能从 Trace 聚合工具成功率。
- 能按工具名统计成功率和平均耗时。
- 能统计 LLM 失败率。
- 能统计转人工次数。
- 最近对话摘要只保留 10 条。
- 能统计用户反馈数量和有用率。
- 能把无用反馈生成 Eval 候选样本。
- 能给无用反馈候选样本打上问题类型，并在 Summary 中聚合数量。
- 能审核候选样本状态。
- 能导出审核通过的 Eval case 草稿。
- 能把 Eval 候选样本保存到本地 JSON 文件，并在新的 `MonitorService` 实例启动时恢复。
- 能把人工补齐断言后的 `APPROVED` 草稿合并进正式回复质量评测文件。
- 能跳过重复 Eval case ID，避免重复写入评测集。
- 能根据工具失败、LLM 失败、低有用反馈率和高风险负反馈生成 Monitor 告警。
- Controller 能返回 MonitorSummary。
- Controller 能记录用户反馈。
- Controller 能返回候选样本列表。
- Controller 能拒绝非法审核状态。
- Controller 能返回导出草稿列表。

## 8. 面试表达

如果面试官问：“你这个项目怎么监控 Agent 表现？”

可以这样回答：

> 我没有一开始就接复杂的监控系统，而是先把 Agent 每一步执行过程记录成 Trace，包括工具调用、LLM 调用、人工转接和耗时。然后做了一层 MonitorService，它不参与 Agent 决策，只消费每轮 ChatResponse 里的 Trace，聚合出工具成功率、LLM 失败率、转人工次数和平均耗时。后面我又补了用户反馈接口，用户可以对每次回复点有用或无用，Monitor 会统计反馈总数和有用率。对于无用反馈，我不会直接写入正式评测集，而是先生成 PENDING_REVIEW 的 Eval 候选样本，保存反馈原因和被评价的助手回复，再由人工审核为 APPROVED 或 REJECTED。候选样本现在会通过本地 JSON 文件持久化，服务重启后还可以继续审核；后续如果接 MySQL，只需要替换 Store 层。审核通过的样本可以导出为 Eval case 草稿，开发者补齐 requiredKeywords / forbiddenKeywords 后，可以通过合并接口写入正式 response_quality_eval_cases.json。合并时会校验样本必须来自 APPROVED 候选池，并跳过重复 case ID，避免污染评测集。现在 Monitor Summary 还会把原始指标进一步转成结构化风险告警，比如工具失败率高、LLM 失败率高、有用反馈率低、不安全承诺和内部实现泄露。这样 Trace 是事实日志，Monitor 是统计视图，告警是行动建议，用户反馈是质量信号，Eval 候选池把在线问题沉淀成回归测试资产。

## 9. 后续扩展

- 按工具名称统计成功率，例如 `order_lookup`、`knowledge_search` 分开看。已完成第一版。
- 按意图统计转人工率。
- 记录窗口指标，例如最近 5 分钟 LLM 失败率。
- 指标超过阈值时触发告警或自动降级。结构化风险告警已完成第一版，自动降级仍作为后续扩展。
- 将 Monitor 指标和 Eval 报告结合，形成“离线质量 + 在线表现”的闭环。
- 将无用反馈沉淀成新的 Eval 候选样本，形成“用户反馈 -> 待审核样本”的闭环。已完成第一版。
- 增加候选样本审核状态流转，例如 `APPROVED` / `REJECTED`。已完成第一版。
- 将 `APPROVED` 候选样本导出为 Eval case 草稿。已完成第一版。
- 将 Eval 候选样本持久化到本地 JSON 文件，支持服务重启后恢复。已完成第一版。
- 将 Eval case 草稿半自动合并到正式评测文件。已完成第一版。
