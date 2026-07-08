# 客服Agent-23-反馈样本进入评测设计

## 1. 为什么要把反馈接入 Eval

前面我们已经完成了两条质量链路：

- Eval：用固定样本离线验证版本质量。
- Monitor：基于真实请求 Trace 和用户反馈观察在线表现。

但是如果两者完全分开，就会出现一个问题：

> 用户在线上觉得某次回答“无用”，但这个问题不会自动沉淀到评测集中，下一次改代码也不一定能防止同类问题复发。

所以这一版新增“反馈样本进入评测候选池”：

```text
用户点击无用
  -> POST /api/monitor/feedback
  -> MonitorService 记录 FeedbackRecord
  -> 自动生成 FeedbackEvalCandidate
  -> FeedbackEvalCandidateStore 写入本地 JSON
  -> Monitor 面板展示待审核候选样本
  -> 人工审核为 APPROVED / REJECTED
  -> 导出 APPROVED 样本为 Eval case 草稿
  -> 人工补齐断言
  -> 半自动合并成正式 Eval case
```

这让项目形成一个更完整的闭环：

```text
离线 Eval 保证版本质量
在线 Monitor 发现真实问题
用户负反馈沉淀成 Eval 候选样本
人工审核后导出 Eval case 草稿
人工补齐断言后合并进评测集
```

## 2. 为什么不直接写入正式评测集

用户点“无用”只是一个质量信号，不一定等于稳定评测样本。

原因包括：

- 用户可能误点。
- 单条反馈可能缺少完整上下文。
- 用户不满意的原因可能是物流、库存等业务事实，而不是 Agent 回复问题。
- 正式评测样本需要有明确输入、期望行为和判定标准。

因此第一版采用候选池：

- `UNHELPFUL` 反馈自动生成候选样本。
- 候选初始状态为 `PENDING_REVIEW`。
- 人工审核后可以变成 `APPROVED` 或 `REJECTED`。
- `APPROVED` 样本可以导出为 Eval case 草稿。
- 候选样本会保存到本地 JSON 文件，项目重启后可以恢复审核状态和 ID 序号。
- 开发者补齐 `requiredKeywords` / `forbiddenKeywords` 等断言后，可以通过合并接口写入正式 Eval case。

这个设计更符合真实工程流程，也更容易在面试中解释清楚。

## 3. 核心模型

### 3.1 FeedbackRecord

```text
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackRecord.java
```

新增字段：

- `assistantReply`：被用户评价的助手回复内容。

为什么要保存这段文本？

如果只保存 `feedbackId` 和 `rating`，后续看到负反馈时并不知道 Agent 到底说了什么。
保存 `assistantReply` 后，开发者可以直接判断这条回复是否适合进入评测集。

### 3.2 FeedbackEvalCandidate

```text
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackEvalCandidate.java
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackEvalCandidateStatus.java
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackEvalCandidateStore.java
```

字段：

- `candidateId`：候选样本 ID。
- `feedbackId`：来源反馈 ID。
- `conversationId`：来源会话。
- `userId`：提交反馈的用户。
- `rating`：当前第一版主要是 `UNHELPFUL`。
- `reason`：用户反馈原因。
- `assistantReply`：被评价回复。
- `suggestedEvalType`：建议进入哪类评测，第一版为 `response_quality_candidate`。
- `issueType`：负反馈问题类型，例如缺少关键信息、危险承诺、政策不清楚等。
- `status`：审核状态，包含 `PENDING_REVIEW`、`APPROVED`、`REJECTED`。
- `reviewNote`：审核备注。
- `reviewedAt`：审核时间，未审核时为 0。
- `timestamp`：生成时间。

`FeedbackEvalCandidateStore` 负责把候选池保存到本地 JSON 文件：

```text
data/feedback-eval-candidates.json
```

它同时保存 `feedbackSequence`、`evalCandidateSequence` 和 `evalCandidateCount`，这样服务重启后新生成的反馈 ID 和候选样本 ID 不会从 1 重新开始，避免前端展示、审核接口和日志排查时出现重复 ID。

### 3.3 FeedbackEvalCaseDraft

```text
src/main/java/com/smartcs/smart_cs_agent/monitor/FeedbackEvalCaseDraft.java
```

这是审核通过样本导出的评测草稿。

字段：

- `id`：建议评测 case id。
- `sourceCandidateId`：来源候选样本。
- `sourceFeedbackId`：来源反馈。
- `conversationId` / `userId`：方便回查上下文。
- `suggestedEvalType`：建议评测类型。
- `issueType`：来源候选样本的问题类型。
- `candidateReply`：被用户评价的回复。
- `requiredKeywords`：第一版留空，后续人工补齐。
- `forbiddenKeywords`：默认带常见危险承诺和内部工具词。
- `expectedPass`：默认 `false`，因为负反馈通常先作为负例草稿。
- `feedbackReason`：用户反馈原因。
- `reviewNote`：人工审核备注。
- `manualTodo`：提醒开发者补齐断言。
- `generatedAt`：草稿生成时间。

## 4. 后端链路

### 4.1 提交反馈

接口：

```http
POST /api/monitor/feedback
Content-Type: application/json

{
  "conversationId": "conv-USER-001-001",
  "userId": "USER-001",
  "rating": "UNHELPFUL",
  "reason": "前端快捷反馈：无用",
  "assistantReply": "可以为您申请退货"
}
```

`MonitorService.recordFeedback(...)` 会：

1. 校验 `conversationId` 和 `rating`。
2. 生成 `FeedbackRecord`。
3. 更新反馈总数、有用数、无用数。
4. 如果是 `UNHELPFUL`，自动创建 `FeedbackEvalCandidate`。

### 4.2 查看候选样本

接口：

```http
GET /api/monitor/feedback/candidates
```

第一版接口返回最近 10 条候选样本。候选池在服务启动时会先从本地 JSON 文件恢复，所以即使项目重启，之前待审核或已审核的样本仍然能继续出现在 Monitor 面板中。

同时，`GET /api/monitor/summary` 也会返回：

- `evalCandidateCount`
- `pendingEvalCandidateCount`
- `approvedEvalCandidateCount`
- `rejectedEvalCandidateCount`
- `evalCandidateIssueCounts`
- `recentEvalCandidates`

这样前端 Monitor 面板可以直接在一个摘要接口里展示所有在线质量信号。

### 4.3 审核候选样本

接口：

```http
POST /api/monitor/feedback/candidates/{candidateId}/review
Content-Type: application/json

{
  "status": "APPROVED",
  "reviewNote": "信息完整，可以进入回复质量评测"
}
```

第一版只允许审核成：

- `APPROVED`：确认可以后续整理成正式 Eval case。
- `REJECTED`：确认暂时不适合作为评测样本。

不允许前端把样本重新改回 `PENDING_REVIEW`，因为 `PENDING_REVIEW` 表示“还没有审核”，不是一个审核结果。

### 4.4 导出 Eval case 草稿

接口：

```http
GET /api/monitor/feedback/candidates/export
```

这个接口只导出 `APPROVED` 候选样本。

为什么不直接写入 `src/test/resources/eval/response_quality_eval_cases.json`？

- 正式评测 case 需要明确的 `requiredKeywords` 和 `forbiddenKeywords`。
- 用户负反馈只能说明这条回复可能不好，不一定能自动推断完整断言。
- 自动写入测试文件容易把不稳定样本混入正式回归测试。

所以第一版选择返回 JSON 草稿，由开发者复制后人工整理。

### 4.5 合并正式 Eval case

接口：

```http
POST /api/monitor/feedback/candidates/merge
Content-Type: application/json

{
  "cases": [
    {
      "sourceCandidateId": "EVAL-CAND-000001",
      "id": "feedback_eval_cand_000001",
      "candidateReply": "可以为您申请退货",
      "requiredKeywords": ["退货时效"],
      "forbiddenKeywords": ["Tool"],
      "expectedPass": false
    }
  ]
}
```

这个接口做三层保护：

- `sourceCandidateId` 必须来自 `APPROVED` 候选样本，不能绕过人工审核。
- 写入正式评测文件时只保留 `ResponseQualityEvaluationTest` 需要的字段，不把审核备注、用户 ID 等调试字段混进去。
- 正例必须提供 `requiredKeywords`，负例必须至少提供 `requiredKeywords` 或 `forbiddenKeywords`，避免没有断言的样本进入回归测试。

合并结果会返回：

- `beforeCount`：合并前正式评测 case 数。
- `afterCount`：合并后正式评测 case 数。
- `mergedCount`：本次真正新增的 case 数。
- `skippedDuplicateIds`：因为 ID 已存在而跳过的 case。

## 5. 前端展示

前端聊天区：

- 普通 Agent 回复下方显示 `有用` / `无用`。
- 提交反馈时会把当前助手回复作为 `assistantReply` 一起传给后端。

Monitor 面板新增：

- `Eval Cand.` 指标卡。
- `Pending` / `Approved` / `Rejected` 状态数量指标卡。
- `Top Issue` 问题类型指标卡。
- `Eval Candidates` 列表。

列表里展示：

- 候选样本 ID。
- 状态 `PENDING_REVIEW`。
- 来源反馈 ID。
- 建议评测类型。
- 问题类型 `issueType`。
- 用户反馈原因。
- 被评价的助手回复。
- 审核按钮：`通过` / `拒绝`。
- 审核后的状态和备注。
- `导出样本` 按钮：复制所有 `APPROVED` 样本的 Eval case 草稿 JSON。
- `合并样本` 按钮：加载 `APPROVED` 草稿，允许开发者补齐断言后提交到正式评测文件。

## 6. 测试覆盖

新增或扩展：

```text
src/test/java/com/smartcs/smart_cs_agent/monitor/MonitorServiceTest.java
src/test/java/com/smartcs/smart_cs_agent/controller/MonitorControllerTest.java
```

覆盖：

- `HELPFUL` 反馈只进入反馈统计，不生成候选样本。
- `UNHELPFUL` 反馈会生成 `FeedbackEvalCandidate`。
- 候选样本能保留 `feedbackId`、`reason`、`assistantReply` 和 `status`。
- 候选样本能保留 `issueType`，并且 Summary 能统计各类问题数量。
- 候选样本能从 `PENDING_REVIEW` 审核为 `APPROVED` 或 `REJECTED`。
- 只有 `APPROVED` 候选样本会导出为 Eval case 草稿。
- 候选样本能持久化到本地 JSON 文件，并在新的 `MonitorService` 实例中恢复。
- `APPROVED` 草稿能在人工补齐断言后合并到正式回复质量评测文件。
- 合并时能跳过重复 case ID，并拒绝没有有效断言的样本。
- Controller 能返回候选样本列表。
- Controller 能拒绝非法审核状态。
- Controller 能返回导出草稿列表。

## 7. 面试表达

如果面试官问：“线上用户反馈怎么反哺评测？”

可以这样回答：

> 我没有把用户负反馈直接写进正式评测集，因为正式 Eval case 需要稳定输入、期望行为和明确判定标准。我的做法是先把用户的 `UNHELPFUL` 反馈沉淀成 `FeedbackEvalCandidate`，初始状态是 `PENDING_REVIEW`，并保存来源会话、反馈原因和被评价的助手回复。候选样本会通过本地 JSON 文件持久化，服务重启后仍然可以继续审核，而且 ID 序号会接着增长。然后我提供了候选样本审核接口，可以把样本标记为 `APPROVED` 或 `REJECTED`，并记录审核备注。审核通过的样本可以导出为 `FeedbackEvalCaseDraft`，开发者补齐 requiredKeywords / forbiddenKeywords 后，可以通过合并接口写入正式 `response_quality_eval_cases.json`。合并时后端会校验样本必须来自 APPROVED 候选池，并跳过重复 ID。这个链路把 Monitor 和 Eval 接起来，能让真实用户问题逐步变成可维护的评测资产。

## 8. 后续优化

后续可以继续增强：

- 记录用户问题和完整上下文，而不仅是助手回复。
- 给候选样本增加人工审核接口，例如 `APPROVED` / `REJECTED`。已完成第一版。
- 支持导出候选样本为 JSON 草稿，供开发者整理为 Eval case。已完成第一版。
- 使用本地 JSON 文件持久化候选样本，支持服务重启后恢复。已完成第一版。
- 支持把审核通过的草稿半自动合并到正式评测文件。已完成第一版。
- 按意图、工具链路、知识库文档统计负反馈来源。
- 接入数据库，支持更多候选样本、分页查询、复杂筛选和长期分析。
