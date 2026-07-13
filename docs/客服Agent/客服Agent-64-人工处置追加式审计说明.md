# 客服 Agent 20.3：人工处置追加式审计说明

## 1. 这一批解决什么问题

20.2 已经能够查询待人工复核记录，并通过 version CAS 执行 retry、confirm、close。但是当时只保存了
最终状态，没有可靠回答下面这些问题：

- 谁执行了操作；
- 操作时实际使用了什么权限；
- 为什么作出这个决定；
- 确认成功依赖了什么证据；
- 操作前后是什么状态和版本；
- 业务状态变化了，但审计是否可能因为单独写失败而丢失。

20.3 增加只追加的人工处置审计，并把原来的人工写入口替换成强制审计写端口。这里完成的仍然是领域和
持久化安全地基，不是对外 HTTP API。ROLE_ADMIN Controller、脱敏 DTO 和稳定 HTTP 错误码属于 20.4。

## 2. 端口为什么拆成三个

```text
PendingHandoffReviewStore
  -> 只读列表和详情

PendingHandoffManualDecisionStore
  -> 唯一人工写入口
  -> 主记录状态变化与审计必须原子提交

PendingHandoffReviewAuditStore
  -> 只读审计时间线
  -> 不提供 append、update 或 delete
```

原来的 `PendingHandoffReviewStore` 同时包含查询和写操作。若继续保留旧的
`retryFromManualReview/confirmFromManualReview/closeFromManualReview`，新 Controller 或以后新增的服务可能
误调用无审计方法。因此 20.3 直接删除旧写方法，让代码在编译期就不能绕开审计。

审计端口也没有公开 `append(event)`。审计事件只能由 Adapter 在成功改变状态的同一个原子操作中生成，
调用方不能自行伪造一条“看起来发生过”的事件。

## 3. 一次人工决定包含哪些可信信息

`PendingHandoffManualDecisionContext` 保存：

| 字段 | 含义 | 安全规则 |
| --- | --- | --- |
| `tenantId` | 当前可信租户 | 必填、trim、最多 64 Unicode 字符 |
| `outboxId` | 待处置记录 | 必填、trim、最多 64 Unicode 字符 |
| `expectedVersion` | 管理页面看到的版本 | 非负，用于 CAS 防止覆盖并发修改 |
| `actorId` | 已验证管理员主体 | 必填、最多 128 字符，不能来自请求体自报身份 |
| `authorityUsed` | 本次实际使用的权限 | 当前只允许 `ADMIN` |
| `reasonCode` | 可统计的结构化原因 | 规范成大写下划线格式，最多 64 字符 |
| `decisionNote` | 管理员自由备注 | 脱敏、压成单行、最多 256 Unicode 字符 |
| `evidenceSource` | 成功证据来源 | `NONE`、管理员核对或售后幂等查询 |

备注在进入 Memory/JDBC Store 前就调用 `SensitiveTextRedactor`。邮箱、手机号、身份证、凭据和长数字会被
替换，换行会被压缩，超长文本按 Unicode code point 截断，避免把 emoji 的代理对从中间切坏。

Trace 层也将 `decisionNote/decision_note/reasonNote/reason_note` 识别为自由文本并统一输出 `[OMITTED]`；
`reasonCode` 和 `action` 等结构化字段仍可以用于排障统计。

## 4. 审计事件的不变量

`PendingHandoffReviewAuditEvent` 是不可变值对象。构造时会拒绝不可能的组合：

| action | 起始状态 | 目标状态 | evidence | scheduledAt |
| --- | --- | --- | --- | --- |
| `RETRY` | `NEEDS_MANUAL_REVIEW` | `RETRY_SCHEDULED` | `NONE` | 必填，当前为服务端立即重试时间 |
| `CONFIRM` | `NEEDS_MANUAL_REVIEW` | `CONFIRMED` | 不能为 `NONE` | 必须为空 |
| `CLOSE` | `NEEDS_MANUAL_REVIEW` | `MANUALLY_CLOSED` | `NONE` | 必须为空 |

它还强制 `toVersion = fromVersion + 1`，保存进入人工复核时的 `failureType` 和实际远端尝试次数快照。
人工决定本身不调用远端，因此不会增加 `attemptCount`。

Java record 默认 `toString()` 会打印所有字段。审计事件覆盖了该方法，只保留动作、状态、版本等排障坐标，
不输出 tenantId、outboxId、actorId 和自由备注。

## 5. Memory 为什么把记录和历史放进同一个 Entry

Memory Adapter 使用 `ConcurrentHashMap<PendingHandoffKey, StoredEntry>`。20.3 的 `StoredEntry` 同时保存：

```text
fingerprint
current PendingHandoffRecord
immutable audit history
```

执行人工决定时先基于同一个旧 Entry 构造新状态和新审计，再调用一次
`records.replace(key, oldEntry, newEntry)`。只有完整 Entry 的 CAS 成功，业务状态和历史才一起可见；CAS 失败时
两者都不会写入。不能先替换 Record 再向另一个 Map 追加审计，否则线程可能在两步之间失败或被并发覆盖。

每次替换都用 `List.copyOf` 创建新的不可变历史，不把可变 List 暴露给查询方。

## 6. JDBC 为什么必须使用同一个事务和行锁

JDBC 三个动作统一执行下面的顺序：

```text
BEGIN
  SELECT 主记录 ... FOR UPDATE
  校验 tenant + outbox、expectedVersion 和 NEEDS_MANUAL_REVIEW
  构造合法审计事件
  INSERT 成功结果                    仅 CONFIRM
  UPDATE 主记录 ... version = version + 1
  INSERT cs_pending_handoff_review_audit
  回读更新后的主记录
COMMIT
```

整个事务回调只使用 `JdbcDatabaseClient` 提供的 connection-bound `operations`。如果在回调里重新调用外层
client，就会打开新连接，SQL 不再属于同一个事务。

`SELECT ... FOR UPDATE` 让两个管理员以及管理员与迟到 Worker 竞争同一把 MySQL 行锁。后取得锁的一方会看到
已经变化的 version 或 status，并收到稳定冲突，不能覆盖先提交的决定。

审计采用 fail-closed，而不是 Monitor 的 best-effort 方式：

- 主表 CAS 失败，成功结果和审计一起回滚；
- 审计 INSERT 返回非 1 行或抛异常，主表和成功结果一起回滚；
- 事务回读失败，前面的所有写入仍然回滚；
- commit 失败由 JDBC Client 显式尝试 rollback，并保留原始异常。

## 7. 数据库表和索引

新表为 `cs_pending_handoff_review_audit`，主要约束如下：

```text
PRIMARY KEY (audit_id)
UNIQUE (tenant_id, outbox_id, to_version)
INDEX (tenant_id, outbox_id, occurred_at, audit_id)
```

唯一键保证同一条记录的同一次目标版本最多对应一条人工事件。分页索引与租户、记录、倒序时间线的查询条件
一致。表不建立外键：审计在主记录未来归档后仍应保留，而且现有主表没有 `(tenant_id, outbox_id)` 复合
唯一键，不应只为外键增加冗余约束。

完整 DDL 位于 `客服Agent-64-人工处置审计建表.sql`。新表不回填历史动作，因为旧数据没有可信 actor、
reason 和 evidence，伪造默认值会让审计比没有审计更具误导性。

## 8. 审计分页和租户隔离

时间线固定按下面顺序查询：

```text
occurredAt DESC, auditId DESC
```

游标同时保存 `occurredAt + auditId`。多个事件发生在同一毫秒时，auditId 作为稳定 tie-break；不能只用时间，
否则翻页会重复或遗漏。Store 每次多取一条判断 `hasMore`，对外 limit 限制为 1..100。

查询必须同时提供 `tenantId + outboxId`。跨租户和不存在都返回空页，不根据 outboxId 是否真实存在泄露信息。
20.4 还必须从已验证 JWT Principal 提取 tenantId，不能相信 URL、请求体或模型生成的租户字段。

## 9. Memory 与 JDBC 的一致契约

| 行为 | Memory | JDBC |
| --- | --- | --- |
| 并发控制 | 整个 `StoredEntry` CAS | 主行 `FOR UPDATE` + version CAS |
| 状态和审计原子性 | 一次 Map replace | 同一个数据库事务 |
| confirm 结果一致性 | Record 与 history 一起替换 | result、主表和 audit 同事务 |
| 审计历史 | Entry 内不可变 List | 只追加审计表 |
| 分页 | Comparator + keyset filter | 复合索引 + keyset SQL |
| 跨租户 | tenant 过滤 | SQL 复合条件 |

实现方式不同，但对上层暴露的成功、Not Found、version conflict、state conflict 和分页语义必须一致。

## 10. 测试关注点

20.3 的自动化测试重点覆盖：

- 上下文原因码规范化、备注脱敏、单行化、长度限制；
- action/status/evidence/scheduledAt 非法组合被拒绝；
- 审计事件安全 `toString()`；
- 三种人工动作都返回匹配的 Record 与 AuditEvent；
- 人工动作不增加远端 `attemptCount`；
- stale version、非法状态、跨租户访问不会留下审计；
- Memory 并发 CAS 最多成功一次且只有一条审计；
- JDBC 审计 INSERT 失败时主表、成功结果和审计一起回滚；
- 同毫秒事件依靠 auditId 稳定翻页；
- Trace 不记录管理员自由备注；
- 真实 MySQL 验证实际事务、行锁、唯一键、分页和跨租户隔离。

20.3 最终验收结果：

```text
默认测试：465 项，0 失败、0 错误、0 跳过
跳过测试 JAR 打包：通过
显式 RealMySqlPersistenceIT：1 项，0 失败、0 错误、0 跳过
```

真实 MySQL IT 在同一真实事务 Connection 的 audit INSERT 前注入异常，确认已经执行过的 result INSERT
和主表 UPDATE 都会 rollback；还将同一 Outbox 的两条真实审计固定为同一毫秒，验证复合游标按 auditId
倒序翻页且不重复、不遗漏。验收后 MySQL 临时容器已停止并删除，数据卷保留。本批未构建 Agent 镜像，
完整 Docker 镜像和 Compose 总验收留在 20.6，不能用 SQL 替身测试冒充真实数据库事务证据。

## 11. 部署与回滚边界

1. 在允许应用执行 DDL 的环境，新版 JDBC Store 会执行 `CREATE TABLE IF NOT EXISTS`。
2. 禁止应用 DDL 的生产环境，发布代码前执行专项 SQL 脚本。
3. 若同名表已经存在，使用 `SHOW CREATE TABLE` 核对完整 17 列和两个二级约束；`IF NOT EXISTS` 不会修复
   错误旧结构。
4. 表建立后才切换到新版代码。不要保留可以绕过审计的旧人工写服务。
5. 回滚旧代码前应先停止管理写流量；审计表可以保留，旧代码不会读取它。

## 12. 当前边界和下一步

20.3 不宣称已经具备完整管理接口。后续仍需要：

- 20.4：ROLE_ADMIN API、JWT 租户和 actor 注入、请求校验、脱敏 DTO、稳定 400/404/409/5xx；
- 20.5：售后平台按 Idempotency-Key 查询结果的可替换 Port；外部平台不支持时必须明确返回 unavailable，
  不能把“查不到”和“确定不存在”混为一谈；
- 20.6：README、架构图、简历手册、全量测试、JAR、真实 MySQL 和 Docker 总验收。

## 13. 初学者建议阅读顺序

1. `PendingHandoffManualAction` 和 `PendingHandoffEvidenceSource`：先理解有限动作与证据来源；
2. `PendingHandoffManualDecisionContext`：看不可信输入如何在进入 Store 前收紧；
3. `PendingHandoffReviewAuditEvent`：理解领域不变量；
4. `PendingHandoffManualDecisionStore`：理解为什么只有一个强制审计写入口；
5. `InMemoryPendingHandoffStore`：用一个 Entry CAS 理解原子性；
6. `JdbcPendingHandoffStore.applyManualDecision()`：对应到行锁和事务；
7. `findAuditPage()`：理解 keyset 分页和复合索引；
8. Memory/JDBC 单元测试：从失败用例反看安全边界；
9. `RealMySqlPersistenceIT`：最后看替身无法证明的真实数据库行为。
