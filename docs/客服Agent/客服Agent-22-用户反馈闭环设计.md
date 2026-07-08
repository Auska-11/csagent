# 客服Agent-22-用户反馈闭环设计

## 1. 为什么要做用户反馈

客服 Agent 不能只停留在“系统觉得自己答得不错”。

真实客服系统里，用户是否满意非常重要：

- 回复是否真的解决了用户问题。
- 用户是否还需要继续追问。
- 某类意图是否经常被用户判定为无用。
- 哪些回复可以沉淀为后续评测样本。

所以在已经有 Trace、Monitor 和 Eval 的基础上，我们继续补一个轻量反馈闭环。

第一版目标不是直接做复杂推荐算法或自动调权，而是先把链路跑通：

```text
用户点击有用/无用
  -> POST /api/monitor/feedback
  -> MonitorService 记录反馈
  -> 无用反馈生成 Eval 候选样本
  -> 本地 JSON 保存候选样本
  -> 人工审核候选样本
  -> 审核通过后导出 Eval case 草稿
  -> 人工补齐断言后合并正式评测集
  -> MonitorSummary 聚合满意度
  -> MonitorSummary 生成风险告警
  -> 前端 Monitor 面板展示最近反馈和候选样本
```

## 2. 当前实现

### 2.1 反馈模型

新增：

```text
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackRating.java
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackRecord.java
```

`FeedbackRating` 使用枚举固定允许值：

- `HELPFUL`
- `UNHELPFUL`

这样可以避免前端随意传 `like`、`good`、`bad` 等字符串，导致后端统计口径混乱。

`FeedbackRecord` 保存：

- `feedbackId`
- `conversationId`
- `userId`
- `rating`
- `reason`
- `assistantReply`
- `timestamp`

### 2.2 接口 DTO

新增：

```text
src/main/java/com/smartcs/smart_cs_agent/controller/FeedbackRequest.java
src/main/java/com/smartcs/smart_cs_agent/controller/FeedbackResponse.java
```

`FeedbackRequest` 使用 Spring Validation 校验：

- `conversationId` 不能为空。
- `rating` 不能为空。
- `reason` 最长 300 字。
- `assistantReply` 最长 2000 字，用来保存被评价的助手回复。

### 2.3 Monitor 接口

新增接口：

```http
POST /api/monitor/feedback
Content-Type: application/json

{
  "conversationId": "conv-USER-001-001",
  "userId": "USER-001",
  "rating": "HELPFUL",
  "reason": "前端快捷反馈：有用",
  "assistantReply": "您的订单已经发货"
}
```

返回：

```json
{
  "success": true,
  "message": "Feedback recorded",
  "feedback": {
    "feedbackId": "FDB-000001",
    "conversationId": "conv-USER-001-001",
    "userId": "USER-001",
    "rating": "HELPFUL",
    "reason": "前端快捷反馈：有用",
    "assistantReply": "您的订单已经发货",
    "timestamp": 1783350000000
  }
}
```

无用反馈还会生成 Eval 候选样本，可通过下面接口查看：

```http
GET /api/monitor/feedback/candidates
```

候选样本可以继续审核：

```http
POST /api/monitor/feedback/candidates/{candidateId}/review
Content-Type: application/json

{
  "status": "APPROVED",
  "reviewNote": "信息完整，可以进入回复质量评测"
}
```

审核通过的候选样本可以导出为 Eval case 草稿：

```http
GET /api/monitor/feedback/candidates/export
```

人工补齐断言后，可以继续合并到正式回复质量评测文件：

```http
POST /api/monitor/feedback/candidates/merge
```

合并接口要求样本必须来自 `APPROVED` 候选池，并且请求中必须包含可执行的评测断言。这样可以避免把“用户觉得无用”这种弱信号直接写进正式回归测试。

Eval 候选样本默认持久化到：

```text
data/feedback-eval-candidates.json
```

部署时可以通过 `SMARTCS_MONITOR_EVAL_CANDIDATES_FILE` 修改保存路径。`data/` 已经加入 `.gitignore`，避免本地演示数据进入 Git 仓库。

## 3. 为什么放在 MonitorService

反馈发生在 Agent 已经回答之后。

它属于“事后观测数据”，和工具成功率、LLM 失败率、人工转接次数一样，都是用来判断 Agent 表现的指标。

所以第一版放在 `MonitorService`，而不是放进 `AgentRuntime`。

这样有几个好处：

- Agent 主链路仍然只负责理解、行动和回复。
- 反馈不会影响本轮对话稳定性。
- Monitor 可以统一聚合 Trace 指标和用户反馈。
- 后续如果要做动态路由权重，可以让路由层读取 Monitor 统计结果，而不是把统计逻辑塞进 Runtime。

## 4. MonitorSummary 新增指标

`GET /api/monitor/summary` 新增：

- `feedbackCount`：累计反馈数。
- `helpfulFeedbackCount`：有用反馈数。
- `unhelpfulFeedbackCount`：无用反馈数。
- `helpfulFeedbackRate`：有用反馈率。
- `recentFeedbacks`：最近 10 条反馈。
- `evalCandidateCount`：累计 Eval 候选样本数。
- `pendingEvalCandidateCount`：当前候选池中待审核样本数。
- `approvedEvalCandidateCount`：当前候选池中已审核通过样本数。
- `rejectedEvalCandidateCount`：当前候选池中已拒绝样本数。
- `evalCandidateIssueCounts`：按负反馈问题类型聚合的候选样本数量。
- `recentEvalCandidates`：最近 10 条由无用反馈生成的候选样本。
- `alerts`：基于反馈和运行指标生成的风险告警，例如有用反馈率偏低、不安全承诺、内部实现泄露和待审核样本积压。

第一版中，最近反馈列表仍然只保留在内存里，用来服务前端调试面板；Eval 候选样本已经通过本地 JSON 文件持久化，避免服务重启后丢失待审核质量样本。后续如果接入 MySQL 或日志平台，可以优先替换 `FeedbackEvalCandidateStore`。

## 5. 前端交互

前端聊天区的每条普通 Agent 回复下方新增：

- `有用`
- `无用`

点击后会调用：

```http
POST /api/monitor/feedback
```

提交成功后：

- 当前消息按钮变为已记录状态。
- Monitor 面板刷新反馈数和有用率。
- Monitor 面板展示最近反馈列表。
- 如果点击无用，Monitor 面板展示 Eval Candidates 待审核样本。
- Monitor 面板展示 `Top Issue`，快速判断当前负反馈主要集中在哪类问题。
- Monitor 面板展示 `Alerts`，把低有用反馈率、不安全承诺和内部工具泄露这类问题直接变成可处理的风险提示。
- 候选样本可以点击 `通过` 或 `拒绝`，审核后展示状态和备注。
- 点击 `导出样本` 可以复制所有审核通过样本的 JSON 草稿。

系统提示消息不显示反馈按钮，避免把错误提示、复制成功提示也算成 Agent 回复质量。

## 6. 测试覆盖

新增或扩展：

```text
src/test/java/com/smartcs/smart_cs_agent/monitor/MonitorServiceTest.java
src/test/java/com/smartcs/smart_cs_agent/controller/MonitorControllerTest.java
```

覆盖：

- 能记录 `HELPFUL` / `UNHELPFUL`。
- 能生成稳定的反馈 ID。
- 能统计反馈总数、有用数、无用数和有用率。
- 能把 `UNHELPFUL` 反馈生成 Eval 候选样本。
- 能把候选样本归类为缺少关键信息、危险承诺、政策不清楚、内部工具泄露、语气体验等问题类型。
- 能根据负反馈类型和有用反馈率生成 Monitor 告警。
- 能审核 Eval 候选样本状态。
- 能导出审核通过的 Eval case 草稿。
- 能把 Eval 候选样本保存到本地 JSON 文件，并在服务重启后恢复。
- 能把人工补齐断言后的草稿合并进正式 `response_quality_eval_cases.json`。
- Controller 能接收反馈请求并写入 Monitor。
- Controller 能返回候选样本列表。
- Controller 能拒绝非法审核状态。
- Controller 能返回导出草稿列表。

前端脚本使用 Node 做语法检查，避免单文件页面因为 JS 语法错误无法加载。

## 7. 面试表达

可以这样讲：

> 我把 Agent 的质量闭环拆成离线 Eval 和在线 Monitor 两部分。Eval 用固定测试集验证版本质量；Monitor 基于真实请求 Trace 统计工具成功率、LLM 失败率和转人工次数。后面我又加了用户反馈闭环，用户可以对每次回复点有用或无用，反馈会进入 MonitorSummary，形成满意度指标和最近反馈列表。对于无用反馈，我会额外生成 Eval 候选样本，保存反馈原因和被评价的助手回复，再通过审核接口标记为 APPROVED 或 REJECTED。候选样本会保存到本地 JSON 文件，服务重启后不会丢失；后续升级数据库时也只需要替换 Store 层。审核通过的样本可以导出为 Eval case 草稿，开发者补齐断言后还能通过合并接口进入正式 response_quality_eval_cases.json。同时 Monitor 会把低有用反馈率、不安全承诺、内部实现泄露等问题生成结构化风险告警。这样项目不只是能回答，还能持续收集“用户是否觉得回答有用”的信号，把真实问题沉淀成回归测试资产，并提示开发者优先处理高风险问题。

## 8. 后续优化

第一版已经完成“反馈采集 -> 聚合 -> 展示”闭环。

后续可以继续增强：

- 反馈原因支持用户手动输入。
- 按意图统计有用率，例如退货、订单、发票分别看。
- 按工具调用链路关联反馈，分析哪些工具结果容易导致无用回复。
- 将负反馈样本自动导出为 Eval 候选样本。已完成第一版。
- 增加候选样本人工审核接口。已完成第一版。
- 将审核通过的候选样本导出为 Eval case 草稿。已完成第一版。
- 将 Eval 候选样本持久化到本地 JSON 文件，支持跨服务重启保留。已完成第一版。
- 将 Eval case 草稿半自动合并到正式评测文件。已完成第一版。
- 将反馈记录和候选样本升级到 MySQL 或日志系统，支持更完整的查询、分页和长期分析。
- 当某类意图负反馈升高时，触发人工复核或降级策略。当前已先完成基于反馈问题类型的风险告警，自动降级留到后续。
