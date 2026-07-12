# 客服Agent-40：人工升级 Workflow 迁移说明

## 1. 本次迁移解决什么问题

迁移前，人工请求、投诉和赔偿纠纷都由 `CustomerServiceSkill.handoffRoute()` 判断，再由
`buildHandoffAction()` 拼接摘要。工单能力仍通过旧 `ToolRegistry` 暴露，`HandoffActionHandler` 还要认识
`tools.HandoffTicketData`。

这条链路虽然能运行，但有三个扩展问题：

1. 新增投诉规则要继续修改已经很大的 Legacy Skill。
2. 活跃 Workflow 各自只识别“人工”，投诉和赔偿可能被误当成正在等待的订单号或退货原因。
3. 本地工具返回 Java DTO，远程 MCP 返回 JSON Map，Handler 需要理解实现差异。

本批次新增 `EscalationWorkflow`、独立 Skill Package、`LocalHandoffCapabilityProvider` 和稳定
`HandoffTicketSummary`，让人工升级成为一个有明确所有者的可插拔业务流程。

## 2. 新增和调整的组件

| 组件 | 职责 |
| --- | --- |
| `skills/escalation/manifest.yaml` | 绑定 `escalation` Workflow，并只授权 `handoff.request` |
| `skills/escalation/SKILL.md` | 说明何时能建工单、何时只能回答政策 |
| `skills/escalation/references/handoff-boundary.md` | 保存升级与非升级的正反例 |
| `workflow/escalation/EscalationWorkflow.java` | 判断升级原因、整理任务线索和生成 `HandoffAction` |
| `workflow/core/GlobalInterruptPolicy.java` | 统一人工、投诉、赔偿/维权分类，并保护政策问题边界 |
| `workflow/core/HandoffSummarySupport.java` | 把当前消息和已收集 Workflow 状态写入真正的工单摘要 |
| `capability/local/LocalHandoffCapabilityProvider.java` | 从服务端上下文取得身份和会话，创建工单 |
| `capability/api/contracts/HandoffTicketSummary.java` | 统一本地 DTO 与远程 MCP JSON 输出 |
| `HandoffActionHandler` | 检查 Skill allowlist、执行 Capability，并结束自动 Workflow |

## 3. 为什么 ComplaintWorkflow 合并进 EscalationWorkflow

原计划分别列出了 `ComplaintWorkflow` 与 `EscalationWorkflow`。实现前检查发现，两者最终都会执行：

```text
识别升级原因
  -> 整理会话摘要
  -> HandoffAction
  -> handoff.request
  -> 返回工单号并结束自动处理
```

如果单独创建 ComplaintWorkflow，就需要复制 Skill 权限、摘要、工单失败、MCP 契约和集成测试。
投诉真正不同的只是原因码 `COMPLAINT`，不是另一套状态机。因此本次把它明确合并成一个升级域：

| 用户表达 | 稳定原因码 |
| --- | --- |
| 明确要求人工/真人客服 | `USER_REQUESTED_HUMAN` |
| 明确投诉或强烈负面反馈 | `COMPLAINT` |
| 赔偿、补偿、维权、12315、律师或起诉 | `COMPENSATION_OR_DISPUTE` |

这样做不是减少扩展性。以后若投诉出现独立取证、材料上传、审核和 SLA 状态机，再把
`ComplaintWorkflow` 从同一分类接口中拆出即可；当前没有这些差异时不提前复制代码。

## 4. 最重要的负向边界

下面两句话虽然都包含“人工”，含义完全不同：

```text
“我要人工客服”         -> 创建工单
“什么情况可以转人工？” -> PolicyQaWorkflow 查询政策，不创建工单
```

`GlobalInterruptPolicy.isHandoffPolicyQuestion()` 会先排除“转人工规则、投诉规则、赔偿规则”等知识问题，
然后才检查升级关键词。`EscalationWorkflow.match()` 和 `PolicyQaWorkflow` 共用同一判断来源，避免两个
Workflow 对同一句话给出相反结论。

这是副作用能力必须具备的设计：不能因为简单关键词误判，就替用户创建真实业务记录。

## 5. 活跃 Workflow 如何被投诉安全打断

Router 为保证多轮任务粘性，会优先恢复等待中的 Workflow。例如：

```text
用户：帮我查订单
Agent：请提供订单号
用户：一直查不到，我要投诉你们
```

第三句不能被当成订单号，也不能等旧流程结束后再升级。现在所有已迁移 Workflow 都先调用共享
`GlobalInterruptPolicy`。它会返回 `HANDOFF + COMPLAINT`，当前 Workflow 清理自己的私有状态，再生成
`HandoffAction`。`HandoffSummarySupport` 会把当前原话和已收集的状态一并写入 summary，避免这些信息
只留在开发者 Trace、没有进入真实工单。Router 和 Runtime 不需要增加订单或投诉特判。

## 6. 身份和会话为什么不再放在参数里

旧调用形态：

```text
handoff.request({
  user_id: "USER-001",
  conversation_id: "conv-001",
  reason: "COMPLAINT",
  summary: "..."
})
```

`user_id` 和 `conversation_id` 如果来自模型参数，就可能被伪造。新调用只传业务数据：

```text
handoff.request({reason, summary})
  -> HandoffActionHandler 构建 ExecutionContext
  -> LocalHandoffCapabilityProvider 读取 principal.userId 和 context.conversationId
```

本地 Provider 会忽略参数中伪造的 `user_id` 和 `conversation_id`。`handoff.request` 要求存在用户身份，
但本地模式不强制已登录，因为未登录用户也应能请求客服帮助；它不会因此开放订单或资料查询。
正式匿名系统应由网关生成稳定匿名主体，而不是直接相信任意客户端 userId。

远程 MCP 调用仍遵守 MCP Server 的认证策略，身份和会话通过协议 `_meta` 传递，不混入业务参数。

## 7. Stable Contract 与幂等

本地 `HandoffTools` 仍返回旧 `HandoffTicketData`，远程 MCP 返回 JSON Map。两种来源现在都转换为：

```text
HandoffTicketSummary
  - ticketId
  - reason
  - status
  - createdAt
```

用户 ID、会话 ID 和完整摘要不会再次进入 Agent Observation。它们已经由 ExecutionContext 和工单系统
保存，减少重复暴露更符合最小数据原则。

`LocalHandoffCapabilityProvider` 实现 `LegacyCapabilityOverride`，生产本地模式会自动隐藏旧
`handoff_create` 注册。Capability 声明为副作用且幂等，Gateway 使用稳定 idempotencyKey 回放成功结果，
网络重试或同轮重复执行不会创建两张工单。

## 8. 测试证据

| 测试 | 主要验证内容 |
| --- | --- |
| `EscalationWorkflowTest` | 三类原因码、任务线索摘要、政策问题不建工单 |
| `EscalationWorkflowIntegrationTest` | 真实 Agent 路由、Package、Handler、Gateway、工单创建和活跃任务打断 |
| `LocalHandoffCapabilityProviderTest` | 伪造身份参数无效、稳定契约、缺参数和幂等回放 |
| `HandoffActionHandlerTest` | Skill allowlist 拒绝、可信 ExecutionContext 和参数最小化 |
| `ToolRegistryCapabilityProviderTest` | 旧工单 DTO 转换为稳定契约，并由 Override 隐藏 |
| `McpCapabilityProviderTest` | 远程工单 JSON 恢复为 `HandoffTicketSummary` |
| `NewArchitectureBoundaryTest` | Runtime、Router、Registry、Gateway 不 import `EscalationWorkflow` |

## 9. 回滚与限制

`CustomerServiceSkill` 中旧 `handoffRoute()` 和 `buildHandoffAction()` 暂时保留一个稳定期。新 Workflow
发生回归时，可以停用独立 Package/Bean 回到 Legacy 路径；不要在本批次直接删除回滚能力。

当前 `HandoffTools` 的 memory 模式仍是演示实现，after-sales-http 失败时也只是创建本地 pending 记录。
正式生产需要把待补建工单写入数据库或消息队列，并增加人工平台 SLA、状态回查和告警。

Phase 7 的生产业务路由迁移到此完成。下一步先做架构验收和稳定期观测，再进入 Phase 8 的持久化、
认证、RBAC 与敏感 Trace 收口；Legacy 删除属于 Phase 9，不应提前执行。
