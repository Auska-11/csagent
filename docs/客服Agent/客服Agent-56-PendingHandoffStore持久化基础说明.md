# 客服 Agent PendingHandoffStore 持久化基础说明

## 1. 本批解决什么问题

第十三批已经停止人工转接假成功，但远端结果未知时只返回技术失败，还没有单独保存一份可供后台核对的请求。

本批新增 `PendingHandoffStore` Outbox 边界：当售后平台超时或异常时，先保存完整创建请求、外部幂等键、用户与会话上下文，再把技术失败返回给上层。JDBC 模式下，记录进入 `cs_pending_handoff` 表，应用重启后仍能恢复。

## 2. 为什么不能把逻辑继续放进 HandoffTools

`HandoffTools` 的职责是组装工单请求并调用售后平台。持久化、并发去重和未来的后台领取属于另一个变化方向。

因此本批拆成：

```text
HandoffTools
  -> 只决定何时记录“结果未知”

PendingHandoffStore
  -> 定义稳定持久化契约

InMemoryPendingHandoffStore
  -> 本地演示和单元测试

JdbcPendingHandoffStore
  -> MySQL 持久化和进程重启恢复
```

以后增加后台任务、管理接口或消息队列时，不需要把这些职责重新堆进 Agent Runtime。

## 3. Outbox 保存哪些数据

一条 `PendingHandoffRecord` 包含：

- Outbox ID。
- 用户 ID、会话 ID、转接原因与摘要。
- 发往售后平台的完整请求字段。
- 必须复用的原始 `Idempotency-Key`。
- 当前状态、失败类型、尝试次数和下次尝试时间。
- version、创建时间和更新时间。

异常原文不会写入 Outbox，只保存如 `IllegalStateException` 这样的异常类型，避免数据库意外记录内部 URL、密码或第三方响应正文。

## 4. 为什么既要唯一键又要请求指纹

JDBC 表对 `idempotency_key` 建立唯一约束，保证并发写入时只能出现一条记录。但只有唯一键还不够：如果程序错误地用同一个 key 发送了不同工单参数，直接返回旧记录会掩盖严重错误。

因此 Store 还会计算 SHA-256 请求指纹：

```text
相同 key + 相同参数
  -> 返回已有 Outbox 记录

相同 key + 不同参数
  -> PendingHandoffConflictException
  -> 不覆盖原始证据
```

指纹使用长度前缀拼接字段，避免简单字符串连接产生边界歧义。

## 5. memory 与 JDBC 模式怎样切换

两个实现使用 Spring 条件装配：

- `smartcs.persistence.mode=memory`：使用 `InMemoryPendingHandoffStore`。
- `smartcs.persistence.mode=jdbc`：使用 `JdbcPendingHandoffStore`。

`HandoffTools` 只依赖接口，不需要写 `if (jdbc)` 分支。手动 new 的旧单元测试默认使用内存实现，便于初学者逐步理解，不会被迫先搭建数据库。

## 6. 双重故障怎样处理

可能同时发生：

```text
售后平台结果未知
  +
Outbox 数据库写入失败
```

此时系统仍然不能绕过 Store、伪造成功或暴露 SQL 异常。`HandoffTools` 会把两层异常统一包装成安全的 `HandoffTicketCreationException`，Gateway 再返回技术失败。

原始 HTTP 异常通过 suppressed cause 留给服务端排查，但用户结果中不会出现数据库地址、密码或第三方错误正文。

## 7. 本批测试覆盖

- 内存 Store 保存完整请求和结果未知状态。
- 相同 key、相同参数返回同一条记录。
- 相同 key、不同参数明确冲突。
- JDBC 唯一键和到期索引存在。
- 重建 JDBC Store 后仍能恢复完整请求。
- 远端异常时 `HandoffTools` 真实写入 Outbox。
- 远端和 Outbox 同时失败时不泄露敏感异常。
- 全量 `mvn test`：383 项，0 失败、0 错误、0 跳过。

## 8. 本批还没有解决什么

本批建立的是持久化基础，还不能称为完整自动恢复：

1. 当前在捕获远端异常后写 Outbox；若进程在外部调用后、进入 catch 前突然退出，仍存在极小崩溃窗口。
2. 还没有调用前 `PREPARED` 记录和成功结果持久化回放。
3. 还没有多实例领取租约和 version CAS 状态更新。
4. 还没有查询远端结果或使用相同幂等键有限补建的后台执行器。
5. 还没有清理已确认历史记录的保留策略。
6. JDBC 测试目前仍是 SQL 替身，真实 MySQL 容器验收尚未完成。

下一批应先把 Outbox 记录前移到外部调用之前，并保存成功结果。这样即使 Capability 幂等结果尚未来得及落库，重试也能从 Outbox 回放真实工单，不再次调用远端。

## 9. 初学者阅读顺序

1. `PendingHandoffStatus`：先理解这条业务记录可能处于哪些状态。
2. `PendingHandoffCommand` 与 `PendingHandoffRecord`：区分“写入命令”和“已持久化记录”。
3. `PendingHandoffStore`：看上层真正依赖的最小接口。
4. `InMemoryPendingHandoffStore`：先理解唯一键和请求指纹。
5. `JdbcPendingHandoffStore`：再把 Map 操作对应到 INSERT、唯一约束和 SELECT。
6. `HandoffTools`：最后看异常路径怎样接入 Outbox。

核心原则：

> Outbox 的价值不是把错误藏起来，而是把“尚未确认的副作用”变成可持久化、可查询、可继续推进的业务状态。
