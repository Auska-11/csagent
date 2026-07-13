# 客服 Agent 人工转接结果未知与假成功修复说明

## 1. 本批解决什么问题

人工转接使用 `after-sales-http` 模式时，Agent 会调用外部售后平台创建工单。旧实现遇到超时或网络异常后，会在当前 JVM 内存中生成一个 `REMOTE_HANDOFF_PENDING` 工单，然后继续告诉用户“已转接”。

这个降级看起来友好，实际有两个严重问题：

1. 内存工单没有进入数据库、消息队列或外部售后平台，进程重启后就会丢失。
2. HTTP 超时只表示 Agent 没有收到响应，不能证明远端没有创建工单。此时再次创建可能产生重复工单。

因此，旧行为属于“无法证明成功，却向用户宣告成功”的假成功语义。

## 2. 当前采用的安全语义

本批没有假装 Outbox 已经完成，而是先建立一个可证明正确的最小语义：

```text
售后平台明确返回工单
  -> 创建成功
  -> 返回真实工单号

售后平台超时或抛出异常
  -> HandoffTicketCreationException
  -> Capability Gateway 转为 TECHNICAL_FAILURE
  -> 幂等 Store 保存失败结果
  -> Handler 明确告诉用户“创建人工工单失败”
```

关键原则是：

> 不知道远端结果时，宁可明确返回技术失败，也不能伪造一个没有可靠落库的成功工单。

## 3. 为什么要使用专用异常

`HandoffTicketCreationException` 表示“本次不能安全确认工单已经创建”。它并不表示远端一定没有执行。

专用异常有三个作用：

- 让代码读者能区分业务拒绝和基础设施失败。
- 让 Capability Gateway 使用统一安全文案，不把内部 URL、密码或异常堆栈暴露给用户。
- 保留原始异常作为服务端排查原因，但不把它直接写进公开结果。

## 4. 为什么相同幂等键不会再次调用远端

第一次调用失败后，Gateway 会把 `TECHNICAL_FAILURE` 保存进 Capability 幂等 Store。相同会话、相同动作再次使用同一个 idempotencyKey 时，会直接回放已保存结果。

```text
第一次调用
  -> 远端异常
  -> 保存 TECHNICAL_FAILURE

相同 idempotencyKey 重试
  -> 命中已完成的幂等记录
  -> 回放 TECHNICAL_FAILURE
  -> 不再次调用售后平台
```

这样可以避免在“远端可能已经成功”的未知状态下立即重复创建工单。

需要注意：用户发起一个全新的人工转接请求时，会使用新的幂等上下文，它仍然可以重新尝试。这里阻止的是同一个副作用请求的盲目重复执行。

## 5. 用户看到的结果怎样变化

旧行为：

```text
远端失败 -> 生成内存 pending 工单 -> 回复“已为您转接”
```

新行为：

```text
远端失败 -> 技术失败 -> 回复“创建人工工单失败，请稍后重试”
```

`HandoffActionHandlerTest` 还会检查失败回复中不能出现“已为您转接”，防止以后修改文案时重新引入假成功。

## 6. 本批测试覆盖

- `HandoffToolsTest`：外部平台失败时抛出专用异常，不再制造 pending 工单，且异常消息不泄露底层错误。
- `LocalHandoffCapabilityProviderTest`：失败被映射为 `TECHNICAL_FAILURE`，相同幂等键只调用一次远端并回放结果。
- `HandoffActionHandlerTest`：用户收到明确失败提示，Trace 记录 `handoff_failed`，回复中不包含成功文案。
- 全量 `mvn test`：377 项，0 失败、0 错误、0 跳过。

## 7. 当前仍未完成的能力

本批修复的是“假成功”，还没有实现可靠的自动补建。生产级方案仍需要：

1. `PendingHandoffStore` memory/JDBC 基础已经完成，但仍需把记录前移到远端调用之前。
2. 能按幂等键查询售后平台，先核对是否已创建，再决定是否补建。
3. 后台任务使用租约、有限重试和退避处理 pending 记录。
4. 明确 `PENDING`、`CONFIRMED`、`FAILED`、`NEEDS_MANUAL_REVIEW` 等状态迁移。
5. 使用真实 MySQL 验证进程重启、并发抢占、重复消费和数据库故障恢复。

第十四批已经补上结果未知记录持久化，详细见 `客服Agent-56-PendingHandoffStore持久化基础说明.md`。
在完整自动处理完成前，项目不会把“已经可靠自动转接”写进简历或对外说明。

## 8. 初学者阅读顺序

1. `HandoffTools.createAfterSalesTicket`：看远端失败怎样变成专用异常。
2. `LocalHandoffCapabilityProvider`：看工具异常怎样经过统一 Gateway。
3. `DefaultCapabilityGateway`：看技术失败怎样保存和回放。
4. `HandoffActionHandler`：看 Capability 结果怎样转换为用户回复和 Trace。
5. 最后阅读本批三个测试，把每一层的输入和输出串起来。

核心原则：

> 对有副作用的外部调用，“结果未知”是一种独立状态，不能擅自解释为成功，也不能无限重试。
