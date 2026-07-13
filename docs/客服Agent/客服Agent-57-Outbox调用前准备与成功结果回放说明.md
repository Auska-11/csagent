# 客服 Agent Outbox 调用前准备与成功结果回放说明

## 1. 本批解决什么问题

第十四批已经能够在捕获售后平台异常后保存 pending 请求，但仍存在一个崩溃窗口：

```text
远端工单已经创建
  -> Agent 进程突然退出
  -> 还没有进入 catch，也没有写 Outbox
  -> 重启后缺少判断依据
```

第十五批把 Outbox 写入前移到任何远端副作用之前，并保存远端明确返回的成功结果。这样即使 Capability 幂等结果尚未落库，重试也可以从 Outbox 回放真实工单，不再次调用售后平台。

## 2. 新的执行顺序

```text
1. 组装售后工单请求
2. PendingHandoffStore.prepare()
3. 只有 created=true 的首次执行者可以调用远端
4. 远端明确成功 -> confirm() 保存成功结果
5. 远端超时/异常 -> markResultUnknown()
6. 后续请求根据 Outbox 状态决定回放或等待核对
```

`prepare()` 返回 `PendingHandoffPreparation`：

- `created=true`：本次创建了 PREPARED 记录，可以首次调用远端。
- `created=false`：记录已存在，不能把同步重试当成一个新副作用。

## 3. 状态怎样约束同步请求

### PREPARED

请求已经可靠保存，但远端调用可能尚未开始、正在执行，或执行后进程退出。同步请求不能判断是哪一种情况，因此不会重复调用。

### RESULT_UNKNOWN

客户端已经观察到超时或异常。远端可能成功，也可能失败，必须由后续核对或补建执行器处理。

### CONFIRMED

Outbox 已保存真实 `ticketId`、状态和创建时间。后续请求直接回放结果，不再访问售后平台。

## 4. 为什么成功结果使用独立表

第十四批已经创建 `cs_pending_handoff`。仅修改 Java 的 `CREATE TABLE IF NOT EXISTS` 不能给已有表可靠增加字段，因此本批新增追加式结果表：

```text
cs_pending_handoff_result
  idempotency_key 主键
  ticket_id
  ticket_status
  ticket_created_at
  recorded_at
```

结果行一旦成功插入，就是远端成功的持久证据。即使进程在“插入结果表”和“更新主表状态”之间退出，重启读取时也会根据结果行恢复为 `CONFIRMED`。

## 5. 为什么成功证据优先于网络异常

并发情况下可能出现：

```text
请求 A 等待响应超时
请求 B 通过核对取得真实成功结果
```

如果 B 已经把成功结果写入 Outbox，A 的网络异常不能覆盖它。Store 会返回 `CONFIRMED`，上层可以使用真实结果。

这体现了一个重要原则：

> 可验证的业务结果，优先级高于某个客户端观察到的传输异常。

## 6. 外部 Idempotency-Key 为什么改成哈希

旧 key 只使用 conversationId 和 reason：

```text
agent:conv-001:handoff:COMPLAINT
```

它有两个问题：

1. Header 直接暴露会话标识。
2. 同一会话先后两次投诉会被错误合并。

新 key 使用 userId、conversationId、reason 和 Capability 操作幂等键共同计算 SHA-256：

```text
agent-handoff-<sha256>
```

它不直接暴露用户与会话 ID，同一个逻辑操作仍然稳定，不同逻辑操作也不会碰撞。

## 7. fail-closed 边界

- `prepare()` 失败：不调用远端，直接技术失败。
- 远端成功但 `confirm()` 无法证明结果已持久化：不向用户宣告成功。
- 已有 PREPARED/RESULT_UNKNOWN：不进行同步盲重试。
- 已有 CONFIRMED：直接回放真实结果。
- 相同 key、不同请求或不同成功结果：明确冲突，不覆盖证据。

## 8. 测试覆盖

- memory/JDBC 调用前 PREPARED。
- 相同请求准备回放与不同请求冲突。
- version CAS 推进 RESULT_UNKNOWN。
- 成功结果持久化和应用重建回放。
- 结果表已写、主表尚未更新时仍恢复 CONFIRMED。
- Outbox 准备失败时远端调用次数为 0。
- 未知状态再次同步调用时远端总调用次数仍为 1。
- 成功结果重试时直接回放，远端总调用次数仍为 1。
- 同一会话的两个操作使用不同不透明 key。
- 全量 `mvn test`：389 项，0 失败、0 错误、0 跳过。

## 9. 当前仍未完成的部分

本批关闭了调用前准备和成功结果回放窗口，但完整自动恢复还需要：

1. 多实例领取租约，确保只有一个后台执行者处理一条记录。
2. 到期查询和批量领取接口。
3. 使用原 Idempotency-Key 的有限补建、退避和最大尝试次数。
4. 查询售后平台已有结果的核对接口；无法确认时进入人工复核。
5. `RETRY_SCHEDULED`、`PROCESSING`、`NEEDS_MANUAL_REVIEW` 的 CAS 状态推进。
6. 已确认记录的保留期与清理任务。
7. 真实 MySQL 并发、重启和掉线恢复验收。

## 10. 初学者阅读顺序

1. `PendingHandoffStatus`：理解 PREPARED、RESULT_UNKNOWN、CONFIRMED。
2. `PendingHandoffPreparation`：理解 created 为什么决定首次执行权。
3. `PendingHandoffStore`：看 prepare、markResultUnknown、confirm 三个动作。
4. `InMemoryPendingHandoffStore`：先理解 Map 中的 CAS 更新。
5. `JdbcPendingHandoffStore`：再看唯一键、版本条件和追加式结果表。
6. `HandoffTools.createAfterSalesTicket`：串起完整调用顺序。
7. 最后阅读三个测试类，把每个崩溃点对应到断言。

核心原则：

> 对外部副作用，先留下可恢复的意图，再执行；拿到成功证据后先持久化，再向上宣告成功。
