# 客服 Agent Outbox 租约与后台有限恢复说明

## 1. 本批解决什么问题

第十五批已经能够在同步链路中准备 Outbox、保存成功结果并回放，但 PREPARED、RESULT_UNKNOWN 记录仍需要人工读取数据库才能继续推进。

第十六批新增可选后台恢复执行器：扫描到期记录，通过短租约保证多实例中只有一个执行者调用售后平台；临时失败时按指数退避有限重试，达到上限后进入人工复核状态。

## 2. 为什么扫描和执行要分层

```text
PendingHandoffStore
  -> 查询到期记录
  -> 原子领取和租约接管
  -> CAS 推进状态

PendingHandoffRecoveryWorker
  -> 使用原请求调用售后平台
  -> 成功确认
  -> 决定退避重试或人工复核
```

数据库竞争属于 Store 职责，定时调度属于 Worker 职责。以后把 Spring `@Scheduled` 换成 MQ 消费者时，不需要重写状态与租约算法。

## 3. 多实例怎样避免重复处理

JDBC 新增 `cs_pending_handoff_claim`：

```text
idempotency_key 主键
owner_token
lease_until
updated_at
```

领取过程：

1. Worker 查询到期记录。
2. 使用随机 ownerToken 插入 claim。
3. 唯一键决定首次领取者。
4. 未过期 claim 存在时，其他实例跳过。
5. 租约过期后，新实例使用旧 ownerToken 和 leaseUntil 作为 CAS 条件接管。
6. 领取成功后，主记录推进为 PROCESSING，并把 nextAttemptAt 设为租约到期时间。

如果实例领取后崩溃，PROCESSING 会在租约到期后重新进入 due 列表，不会永久卡住。

## 4. 为什么需要 PREPARED 宽限期

同步线程执行：

```text
prepare PREPARED
  -> 调用远端
  -> confirm
```

如果后台在 PREPARED 刚写入时立即领取，会与仍在运行的同步线程同时调用远端。本批增加 `prepared-grace-ms`，默认 30 秒；只有超过宽限仍没有确认的记录才允许后台接管。

默认售后读取超时是 10 秒，宽限期为 30 秒。部署时应保持：

> prepared-grace-ms 大于同步售后请求可能占用的最长时间。

## 5. 重试和人工复核

Worker 每次都复用 Outbox 保存的完整请求和原 Idempotency-Key。

```text
执行成功
  -> CONFIRMED
  -> 保存并回放真实工单

临时失败且未到上限
  -> RETRY_SCHEDULED
  -> 指数退避

达到 max-attempts
  -> NEEDS_MANUAL_REVIEW
  -> 不再自动调用
```

默认基础延迟 5 秒，最大延迟 60 秒，最多尝试 3 次。所有数值都必须大于 0，避免“0 表示无限”等隐蔽配置错误。

## 6. 为什么恢复默认关闭

HTTP Header 中带 Idempotency-Key，不代表下游一定正确实现幂等。如果售后平台忽略这个 Header，自动补建可能生成重复工单。

因此：

```text
SMARTCS_HANDOFF_RECOVERY_ENABLED=false
```

只有完成下游联调，确认相同 key 始终返回同一业务工单后，才应设为 `true`。默认关闭是安全门，不代表功能没有实现。

## 7. 日志安全

单条恢复失败不会中断整个批次。Worker 警告日志只记录异常类型，不记录：

- userId、conversationId。
- Idempotency-Key。
- 工单标题和描述。
- HTTP/SQL 异常原文。
- 内部 URL 或凭据。

## 8. 配置项

| 配置 | 默认值 | 作用 |
| --- | ---: | --- |
| enabled | false | 是否启动调度线程和 Worker |
| batch-size | 10 | 每批最多扫描多少条 |
| fixed-delay-ms | 5000 | 两批扫描之间的延迟 |
| prepared-grace-ms | 30000 | 首次同步执行宽限期 |
| lease-ms | 30000 | Worker 处理租约 |
| retry-delay-ms | 5000 | 第一次重试基础延迟 |
| max-retry-delay-ms | 60000 | 指数退避上限 |
| max-attempts | 3 | 自动尝试上限 |

`application.yml`、`.env.example` 和 `docker-compose.yml` 已同步这些配置。

## 9. 测试覆盖

- PREPARED 宽限期内不能被后台领取。
- memory/JDBC 到期查询。
- 未过期租约只能有一个 owner。
- 租约到期后新 owner 可以 CAS 接管。
- 迟到 owner 不能更新新 owner 的记录。
- 临时失败进入 RETRY_SCHEDULED，退避前不调用远端。
- 达到最大次数进入 NEEDS_MANUAL_REVIEW。
- 成功恢复保存 CONFIRMED 和真实 ticketId。
- 非正数配置启动前失败。
- 全量 `mvn test`：394 项，0 失败、0 错误、0 跳过。

## 10. 当前仍未完成的边界

1. 售后平台客户端只有创建接口，没有“按 Idempotency-Key 查询已有工单”的核对接口。
2. NEEDS_MANUAL_REVIEW 已有状态，但还没有 ADMIN 管理 API、页面和人工处理审计。
3. 没有租约心跳；当前租约必须配置得长于单次售后 HTTP 上限。
4. 到期查询在 JDBC 中使用小批量 N+1 读取成功结果，当前批量 10 可接受，规模扩大后应改为 JOIN。
5. 没有已确认 Outbox 的保留期和清理任务。
6. 当前 JDBC 测试仍是 SQL 语义替身，真实 MySQL 多实例和掉线恢复尚未验收。

## 11. 初学者阅读顺序

1. `PendingHandoffClaim`：理解 ownerToken 和 leaseUntil。
2. `PendingHandoffStore` 新增的 findDue、tryClaim、scheduleRetry。
3. `InMemoryPendingHandoffStore`：先看 Map 如何模拟租约。
4. `JdbcPendingHandoffStore`：再看唯一 claim 表和 CAS SQL。
5. `PendingHandoffRecoveryProperties`：理解安全默认值。
6. `PendingHandoffRecoveryWorker`：看业务推进流程。
7. 最后阅读 Worker 和两种 Store 测试。

核心原则：

> 后台重试必须同时具备幂等键、唯一领取、时间边界、次数上限和人工兜底；缺少任何一个都可能把恢复机制变成故障放大器。
