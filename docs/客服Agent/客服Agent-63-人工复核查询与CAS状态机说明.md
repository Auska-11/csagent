# 客服 Agent 人工复核查询与 CAS 状态机说明

> 版本说明：本文记录 20.2 当时的状态机地基。20.3 已将 `PendingHandoffReviewStore` 收紧为只读，删除下文
> 三个 `*FromManualReview` 写方法，并用强制追加审计的 `PendingHandoffManualDecisionStore` 替代。
> 新代码请以 `客服Agent-64-人工处置追加式审计说明.md` 为准；本文中的旧方法名只用于理解演进历史。

## 1. 本小批次解决了什么问题

后台恢复器把自动处理不了的人工转接记录推进到 `NEEDS_MANUAL_REVIEW` 后，系统以前只有“记录已经需要
人工核对”这个状态，却没有一套稳定的 Store 接口让后续管理端安全地查看和处置它。

Phase 8 第二十批的 20.2 小批次补上了这层持久化能力：

- 新增独立的 `PendingHandoffReviewStore`，负责人工复核查询和人工状态迁移；
- 支持按租户、状态和游标分页查询记录；
- 支持在当前租户内按 `outboxId` 查询详情；
- 支持人工选择“重新排队”“依据外部证据确认成功”或“人工关闭”；
- 所有人工写操作都要求调用者提交读到的 `version`，通过 CAS 防止覆盖并发修改；
- memory 与 JDBC Adapter 实现相同的业务语义；
- JDBC 的人工操作使用事务、行锁、状态条件和版本条件，避免出现半成功结果；
- JDBC 查询把追加式成功结果表作为权威证据，主表状态短暂滞后时也不会把已成功记录继续列为待复核。

这里完成的是 **Store 层的安全地基**，还不是已经对外开放的管理 API。追加式人工操作审计属于 20.3，
ADMIN HTTP API、RBAC、脱敏 DTO 和稳定错误响应属于 20.4。

## 2. 为什么要把 Review Store 与 Recovery Store 分开

后台自动恢复和人工复核面对的是同一份 Outbox 数据，但它们的职责不同：

| 端口 | 使用者 | 主要职责 |
| --- | --- | --- |
| `PendingHandoffStore` | 同步创建链路、`PendingHandoffRecoveryWorker` | PREPARED、结果未知、到期扫描、机器租约、自动重试和远端结果回放 |
| `PendingHandoffReviewStore` | 后续 ADMIN 应用服务/Controller | 租户内列表、详情和人工 retry/confirm/close |

如果把两类方法都继续塞进 `PendingHandoffStore`，上层很容易拿到自己不该使用的机器租约操作，接口也会
越来越大。现在两个 Port 分开表达职责；为了避免重复维护两套数据，memory 和 JDBC Adapter 暂时同时实现
这两个接口。

```text
Recovery Worker ----> PendingHandoffStore ---------+
                                                    +--> 同一份 Outbox 数据
ADMIN 应用层 -------> PendingHandoffReviewStore ---+
```

这是一种“端口按调用者职责拆分，Adapter 可以复用”的做法。以后即使把人工复核迁移到独立模块，上层也不
需要依赖 Worker 的内部协议。

## 3. 人工状态机不是一条必须依次走完的直线

人工处置只允许从 `NEEDS_MANUAL_REVIEW` 出发，并在三种决定中选择一种：

```text
                              +-> RETRY_SCHEDULED -- 后续由 Worker 重新领取
                              |
NEEDS_MANUAL_REVIEW ----------+-> CONFIRMED ------- 已有可信的远端成功证据
                              |
                              +-> MANUALLY_CLOSED -- 明确无需创建/关联工单
```

三条分支的含义如下：

| 操作 | 新状态 | 修改内容 | 不做什么 |
| --- | --- | --- | --- |
| `retryFromManualReview` | `RETRY_SCHEDULED` | 设置下次执行时间和 `MANUAL_RETRY_REQUESTED` | 不在管理请求线程调用远端，不增加 `attemptCount` |
| `confirmFromManualReview` | `CONFIRMED` | 保存可信的 `AfterSalesTicketCreateResult`，清除待执行时间 | 不伪造工单结果，不增加 `attemptCount` |
| `closeFromManualReview` | `MANUALLY_CLOSED` | 设置关闭标记并令 `nextAttemptAt=Long.MAX_VALUE` | 不把关闭解释为远端成功，不允许 Worker 再扫描 |

`attemptCount` 只统计真正发起过的远端尝试。管理员只是改变调度或录入外部证据，没有调用远端，因此不能
让次数加一，否则重试上限会被错误消耗。

`MANUALLY_CLOSED` 是不可自动恢复的终态。普通确认和结果未知更新也会拒绝从该状态继续推进，避免以后某个
迟到 Worker 把已经人工关闭的记录“复活”。JDBC 普通确认会先锁定同一条主记录，再检查关闭终态并事务性
写入成功证据；人工 close 也竞争同一把行锁，所以关闭先完成时迟到结果无法写入，成功证据先完成时 close
会看到有效 `CONFIRMED` 并拒绝覆盖。人工 confirm/close/retry 同样不能对 `CONFIRMED`、
`RETRY_SCHEDULED` 或其他非待复核状态再次执行。

## 4. 租户隔离为什么必须从查询条件开始

复核接口的所有读写方法都显式接收 `tenantId`：

```java
Optional<PendingHandoffRecord> findByTenantAndOutboxId(
        String tenantId,
        String outboxId
);
```

列表先按 tenant 过滤，详情和写操作使用 `tenantId + outboxId` 定位记录。跨租户访问和记录不存在都表现为
“当前租户内找不到”，不会先按全局 `outboxId` 找到记录再做权限判断。这一点很重要，因为后一种做法可能
通过不同错误或耗时泄露另一个租户是否存在该记录。

Store 只负责数据归属过滤，不负责认证授权。20.4 的 Controller 必须从服务端验证过的 JWT Principal 取得
tenantId，并要求 `ROLE_ADMIN`；不得接受客户端自行提交一个 tenantId 后直接相信它。

## 5. Keyset 游标分页如何工作

列表按下面两个字段倒序排列：

```text
updatedAt DESC, outboxId DESC
```

`updatedAt` 让最近变化的记录排在前面；`outboxId` 是同一毫秒内的稳定第二排序键。游标保存上一页最后一条
记录的这两个值：

```java
new PendingHandoffReviewCursor(last.updatedAt(), last.outboxId())
```

查询下一页时，只读取严格位于游标之后的记录：

```text
updated_at < cursor.updatedAt
OR (updated_at = cursor.updatedAt AND outbox_id < cursor.outboxId)
```

这叫 keyset pagination。它没有 `OFFSET 100000` 这种越翻越慢的问题，也比“只用时间戳”更不容易在相同
时间值下重复或漏掉记录。Store 会多取一条（`limit + 1`）判断 `hasMore`，多出的记录不会返回给上层。

边界规则：

- `limit` 只能是 1 到 100，防止管理端一次拉取无界敏感数据；
- 第一页传 `cursor=null`；
- 只有 `hasMore=true` 时才返回 `nextCursor`；
- Store 使用结构化游标，不依赖 HTTP 层未来选择的 Base64/JSON 编码格式；
- 这是稳定遍历语义，不是全局静态快照。翻页期间若记录状态或 `updatedAt` 改变，调用方应按管理列表的实时
  数据语义处理，不能把游标当成审计归档。

JDBC 列表在同一个事务中先查 key，再投影完整记录，避免 N+1 回读使用不同连接看到互相矛盾的页面。
目标 MySQL 的事务快照还会让这一组读取保持一致。

## 6. 为什么要同时检查 version 和 status

管理员打开详情页时会读到记录当前的 `version`。提交操作时必须把这个版本作为 `expectedVersion` 原样带回：

```text
管理员 A 读取 version=7
管理员 B 读取 version=7
管理员 A 确认成功：version 7 -> 8
管理员 B 再关闭：expectedVersion 仍为 7，CAS 失败
```

如果不检查版本，B 的旧页面可能覆盖 A 刚刚录入的真实工单结果。只检查 version 仍不够，还必须要求当前
状态仍为 `NEEDS_MANUAL_REVIEW`，因此 JDBC 更新条件同时包含：

```sql
WHERE tenant_id = ?
  AND outbox_id = ?
  AND version = ?
  AND status = 'NEEDS_MANUAL_REVIEW'
```

成功更新后 `version = version + 1`。memory Adapter 通过 `ConcurrentHashMap.replace(old, new)` 完成同样的
原子替换；JDBC Adapter 通过行锁和条件 UPDATE 完成。这样不是“最后一次提交的人覆盖前一个人”，而是只有
第一个基于最新版本作出决定的人成功，其他人必须刷新详情后重新判断。

## 7. JDBC 为什么还需要事务和 `SELECT ... FOR UPDATE`

JDBC 人工操作的核心流程是：

```text
开始事务
  -> 按 tenantId + outboxId SELECT ... FOR UPDATE
  -> 校验 version 与 NEEDS_MANUAL_REVIEW
  -> 执行条件 UPDATE
  -> 在同一事务内回读最终记录
提交事务
```

行锁让同一记录上的并发处置串行进入关键区；UPDATE 的 version/status 条件仍然保留，作为第二道并发保护。
retry 和 close 也在锁行、更新和回读完成后才提交，防止 Worker 在“更新已提交但返回对象尚未读取”的窗口
抢先推进状态，导致接口返回一个与本次操作不一致的结果。

人工 confirm 还多一步保存权威结果：

```text
SELECT ... FOR UPDATE
  -> 校验
  -> INSERT cs_pending_handoff_result
  -> CAS 更新主表为 CONFIRMED
  -> 同事务回读
  -> commit
```

如果主表 CAS 失败，结果 INSERT 会一起 rollback，不会留下“结果表写成功、这次人工确认却返回失败”的
半事务。`JdbcDatabaseClient.inTransaction()` 把同一 Connection 绑定为 `JdbcDatabaseOperations`；事务
回调里只能使用传入的 operations，重新调用外层 client 会另开连接并逃出事务。业务异常、SQL 异常或 commit
失败都会尝试 rollback，rollback 自身失败则作为 suppressed 异常保留原始证据。

后台 Worker 使用的普通 `confirm()` 还有一个不同的崩溃恢复要求：远端已经明确成功后，结果证据必须先于
主表状态投影持久化。它会在事务中执行“锁主行 -> 检查 `MANUALLY_CLOSED` -> 写结果 -> commit”，再把
主表投影为 `CONFIRMED`。若进程刚好在两步之间退出，重启后仍能从结果表恢复成功；同时，行锁保证人工
关闭与结果写入有确定先后顺序，不会产生新的 `MANUALLY_CLOSED + result` 冲突组合。

## 8. 为什么 JDBC 列表不能只相信主表 status

`cs_pending_handoff_result` 是追加式成功证据。历史设计允许在结果表已经落盘、主表状态还没来得及更新时
进程退出；重启后，读取逻辑通常必须把这条记录解释成 `CONFIRMED`。唯一例外是业务人员明确设置的
`MANUALLY_CLOSED`：它是一道优先级更高的终态围栏。新实现通过行锁阻止这种组合继续产生，读取侧仍保留
兼容围栏，确保历史异常若已经留下 `MANUALLY_CLOSED + result` 脏数据，也不会把关闭记录伪装成成功。

因此 JDBC 的有效状态筛选遵循：

- 查询 `CONFIRMED`：主表状态为 CONFIRMED，**或者**主表不是 MANUALLY_CLOSED 且结果表存在成功证据；
- 查询 `MANUALLY_CLOSED`：只相信主表关闭状态，即使历史异常残留结果行也继续列为关闭；
- 查询其他状态：主表状态匹配，**并且**结果表不存在成功证据。

这个条件在 SQL 分页取数前执行，而不是先取满一页再在 Java 中丢弃。否则一条“主表仍显示待复核、结果表
已经成功”的记录会占掉页面名额，造成错误的 `hasMore`，甚至让正常待复核记录暂时看不见。

Recovery Worker 的 `findDue()` 也必须在 SQL `LIMIT` 之前使用相同的 `NOT EXISTS result` 围栏。否则如果
前 `batchSize` 条都是“主表状态滞后、结果证据已存在”的记录，Java 虽然会在回读时把它们过滤掉，它们却
会在每轮查询中持续占满批次，使后面的真实待恢复任务长期饥饿。Java 回读过滤仍然保留，用来防御取 key
之后才并发写入结果的窄窗口。

## 9. Memory 与 JDBC 如何保持一致

两种 Adapter 必须向上层提供同样的可观察行为：

| 行为 | Memory | JDBC |
| --- | --- | --- |
| 租户内列表和详情 | Map 过滤 | tenant 条件 SQL |
| 排序与游标 | Java Comparator + 相同游标判断 | `ORDER BY` + 相同 keyset 条件 |
| 页面是否还有数据 | 多取一条 | 多取一条 |
| 版本冲突 | 原子 `replace` 失败 | 行锁/条件 UPDATE 失败 |
| 状态冲突 | 更新函数内校验 | 锁行后校验 |
| 人工 retry/confirm/close | version 加一，attempt 不变 | version 加一，attempt 不变 |
| 跨租户详情 | `Optional.empty()` | `Optional.empty()` |

实现手段可以不同，契约必须一致。JDBC 额外承担持久化崩溃恢复，因此会读取权威结果表；memory 模式没有
跨进程的“结果表已写、主表未写”窗口，但正常查询和状态迁移的对外语义仍与 JDBC 相同。

## 10. 异常边界与未来 HTTP 映射

当前 Store 使用的主要异常如下：

| 异常 | 含义 | 20.4 建议的 HTTP 语义 |
| --- | --- | --- |
| `PendingHandoffReviewNotFoundException` | 当前租户内不存在；也包含跨租户访问 | 404，不区分两种原因 |
| `PendingHandoffVersionConflictException` | `expectedVersion` 已过期，或并发 CAS 未命中 | 409，提示刷新后重试 |
| `PendingHandoffStateConflictException` | 记录存在，但当前状态不允许该人工操作 | 409，可返回稳定错误码和脱敏后的当前状态 |
| `IllegalArgumentException` / `NullPointerException` | 空 tenant/outbox、非法 limit、负时间或缺少结果 | 由 Controller DTO Validation 尽量提前转成 400 |
| JDBC 内部异常 | 连接、查询、commit 等技术失败 | 统一安全 5xx，不能把 SQL、账号或底层异常原文返回客户端 |

`PendingHandoffReviewNotFoundException` 本身不保存 tenantId/outboxId，避免通用异常日志无意打印敏感标识。
这里的 HTTP 映射是 20.4 的实现契约说明，20.2 还没有新增 Controller，不能把 Store 异常直接暴露给公网。

## 11. 测试覆盖与最终验收方式

当前代码中的专项测试覆盖以下行为：

- `InMemoryPendingHandoffStoreTest`：租户分页、无重复游标遍历、详情隔离、三种人工操作、version/state
  冲突、跨租户 Not Found、关闭终态和人工操作不增加 attempt；
- `JdbcPendingHandoffStoreTest`：与 memory 相同的契约、结果表权威投影、事务回读、确认 CAS 失败时结果表
  一起回滚，结果证据在 due/review 的 LIMIT 前过滤，以及关闭终态优先于遗留结果行、关闭后普通确认不会
  新增结果；
- `JdbcDatabaseClientTimeoutTest`：事务连接、commit/rollback 相关 JDBC 行为；
- `RealMySqlPersistenceIT`：真实 MySQL keyset 分页、主表滞后投影、retry/confirm/close、跨租户隐藏，以及
  两个 JDBC Client 并发确认时只能有一个成功。

提交 20.2 前应执行：

```bash
bash mvnw -q test
bash mvnw -q -DskipTests package
```

真实 MySQL 集成测试仍按项目既有显式方式执行，不应被默认 memory 测试替代。20.2 最终实测结果为：

```text
默认 clean test：451 项，0 失败、0 错误、0 跳过
显式真实 MySQL IT：1 项，0 失败、0 错误、0 跳过
跳过测试打包：通过
```

真实 MySQL 用例还使用两个独立 Client 和 Latch 固定执行顺序，验证 close 先提交时迟到 confirm 返回状态
冲突且结果表没有脏行，confirm 先提交时后续 close 被拒绝。验收复用了旧 Docker 卷，因此先实际执行
20.1 租户迁移脚本并确认三张表的空租户计数均为 0；测试结束后容器已停止并删除，持久卷保留。

## 12. 配置与使用边界

20.2 没有新增面向公网的配置开关。Store Adapter 仍由现有持久化模式决定：

```yaml
smartcs:
  persistence:
    mode: memory # 或 jdbc
```

- `memory` 适合本地学习、单元测试和无持久化 Demo，进程重启后数据会丢失；
- `jdbc` 才适合需要重启恢复和多实例协作的场景，必须先完成 20.1 的租户复合键数据库迁移；
- 若希望 retry 后由后台 Worker 继续处理，还要按既有规则显式启用
  `smartcs.handoff.recovery.enabled`，并先确认外部售后平台真正支持同一 Idempotency-Key 的幂等调用；
- 人工 retry 只是排队，不代表远端已经成功；
- 人工 confirm 必须来自可核验的售后平台证据，不应允许管理员随意构造 ticketId；20.4 的请求 DTO 仍需
  做长度、格式和空值校验；
- 人工 close 表示业务确认“不再创建或关联”，不是删除记录，也不是 CONFIRMED；
- Store 当前返回领域 Record，其中包含内部请求和上下文。20.4 必须转换成脱敏 DTO，不能直接序列化
  `PendingHandoffRecord`；
- 20.2 尚未记录“谁在何时因为什么理由进行了处置”。在 20.3 追加式审计完成前，不应宣称人工操作已经
  满足完整审计合规要求。

## 13. 初学者建议阅读顺序

1. `PendingHandoffStatus`：先理解 Outbox 的所有状态；
2. `PendingHandoffReviewStore`：只看上层可以做哪些复核操作；
3. `PendingHandoffReviewCursor` 与 `PendingHandoffReviewPage`：理解 keyset 分页契约；
4. `InMemoryPendingHandoffStore`：用 Map 和 Comparator 理解最直观的实现；
5. `JdbcPendingHandoffStore.findReviewPage()`：把相同语义对应到 SQL；
6. `updateManualReviewState()`：理解 retry/close 的事务 CAS；
7. `confirmFromManualReview()`：理解为什么结果表 INSERT 和主表 UPDATE 必须在同一事务；
8. `JdbcDatabaseOperations` 与 `JdbcDatabaseClient.inTransaction()`：最后理解同一 Connection、commit 和
   rollback；
9. memory/JDBC 单元测试与 `RealMySqlPersistenceIT`：通过用例反向验证契约。

下一步 20.3 会增加只追加、不覆盖的人工处置审计 Store；20.4 再在这些安全边界之上开放带 RBAC、脱敏
DTO 和稳定 404/409 的 ADMIN API。
