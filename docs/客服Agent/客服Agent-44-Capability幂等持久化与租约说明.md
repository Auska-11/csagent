# 客服Agent-44：Capability 幂等持久化与租约说明

## 1. 本批次结论

Phase 8 第三批把副作用 Capability 的幂等记录从单进程 Map 拆成了可替换 SPI：

```text
DefaultCapabilityGateway
          |
          v
CapabilityIdempotencyStore
    |- InMemoryCapabilityIdempotencyStore
    `- JdbcCapabilityIdempotencyStore
```

现在具备以下能力：

- memory 模式继续使用进程内 `CompletableFuture` 合并并发调用。
- jdbc 模式使用 MySQL 唯一主键竞争同一个幂等键的执行权。
- 两个 Agent 实例同时收到同一请求时，只有一个实例进入 Provider。
- 成功和业务拒绝会保存为 JSON，服务重启后可以直接回放。
- 技术失败也会稳定保存，避免“远端已成功、响应却超时”时用同 key 重复执行。
- 相同幂等键配上不同请求参数会返回 `IDEMPOTENCY_KEY_CONFLICT`。
- 幂等作用域包含可信 tenantId、userId 和 conversationId，不会跨用户回放。
- 执行实例崩溃后，其他实例只能等租约过期再接管。
- 从进入 Provider 到结果完成落库期间都由后台心跳续租，慢调用和慢落库不会被并行接管。
- 幂等数据库不可用时 Gateway 失败关闭，不会绕过 Store 执行副作用。

这解决了“多个正常运行实例同时执行同一副作用”的主要风险，但不等于绝对的 exactly-once。
Provider 已经产生副作用、应用却在保存完成记录前崩溃时，租约过期后仍可能再次调用 Provider。
因此外部系统仍应识别同一个 idempotencyKey，本地领域表也应增加业务唯一约束。

## 2. 为什么 Session CAS 还不够

上一批已经为会话增加 revision CAS。它能阻止旧会话状态覆盖新状态，但调用顺序可能是：

```text
实例 A 加载会话 revision=3
实例 B 加载会话 revision=3

实例 A 调用 return.apply   -> 已产生退货记录
实例 B 调用 return.apply   -> 又产生一条退货记录

实例 A 保存 revision=4     -> 成功
实例 B 保存 revision=4     -> CAS 冲突
```

虽然 B 最终没有覆盖会话，但它调用 Provider 时副作用已经发生。CAS 保护的是“状态保存”，不是
“副作用执行”。因此副作用必须在进入 Provider 之前拥有独立的跨实例协调边界。

## 3. 为什么拆成 Store SPI

旧 `CapabilityIdempotencyStore` 同时是具体内存实现和 Gateway 的直接依赖。这意味着以后换 MySQL
或 Redis 时，要修改 Gateway 的核心代码。

现在职责被拆开：

| 组件 | 负责什么 | 不负责什么 |
| --- | --- | --- |
| `DefaultCapabilityGateway` | 身份、deadline、调用 Provider、把 Store 异常转成稳定结果 | SQL、租约和轮询 |
| `CapabilityIdempotencyStore` | 定义“同 key 同请求只执行一次”的契约 | 具体存储技术 |
| `InMemoryCapabilityIdempotencyStore` | 单 JVM 并发合并和结果回放 | 跨进程协调 |
| `JdbcCapabilityIdempotencyStore` | 唯一键、租约、结果持久化和跨实例等待 | 退货、工单等业务解释 |
| `CapabilityResultCodec` | 结果 JSON 和 schemaVersion | Provider 路由 |

未来增加 Redis 时，只需新增：

```java
public class RedisCapabilityIdempotencyStore
        implements CapabilityIdempotencyStore {
    // 使用 SET NX、Lua 或事务实现同一份 SPI
}
```

Gateway、Workflow、Skill Package 和 MCP Adapter 都不需要认识 Redis。

## 4. JDBC 执行流程

### 4.1 第一个请求抢占执行权

Gateway 先把可信身份和客户端 key 组合成无歧义作用域：

```text
length(tenantId):tenantId
+ length(userId):userId
+ length(conversationId):conversationId
+ length(capabilityId):capabilityId
+ length(idempotencyKey):idempotencyKey
```

不能只使用客户端给出的 idempotencyKey。否则同一租户的用户 B 可以复用用户 A 的 key，在 Provider
执行订单所有权校验前直接拿到 A 的退货单号或工单号。

Store 再执行一条 `INSERT`：

```text
storage_key_hash = SHA-256(storageKey)
request_fingerprint_hash = SHA-256(requestFingerprint)
status = IN_PROGRESS
owner_token = 本次执行者随机 UUID
lease_until = 当前时间 + leaseMs
```

`storage_key_hash` 是主键。只有一个实例可以插入成功，因此只有它可以调用 Provider。

### 4.2 第二个请求遇到已有记录

第二个实例插入时会撞到唯一主键，然后读取现有记录：

```text
                   +-- fingerprint 不同 --> 冲突，禁止回放
已有记录 ----------+-- COMPLETED ---------> 解码并回放结果
                   `-- IN_PROGRESS --------> 等待或检查租约
```

它不会因为“幂等层正在忙”就直接调用 Provider。绕过幂等层虽然看起来能提高可用性，却会把一次
数据库故障变成重复退货或重复工单，因此这里必须失败关闭。

### 4.3 Provider 完成

Provider 返回后分成三类：

| Outcome | Store 行为 | 原因 |
| --- | --- | --- |
| `SUCCESS` | 保存结果并改为 `COMPLETED` | 后续必须稳定回放 |
| `BUSINESS_REJECTED` | 保存结果并改为 `COMPLETED` | 业务拒绝也是确定结果，不能反复执行 |
| `TECHNICAL_FAILURE` | 保存结果并改为 `COMPLETED` | 超时可能表示副作用已成功但响应未知，不能自动重试 |

完成更新同时检查 `owner_token`。旧执行者不能覆盖已经被新实例接管的记录。

Gateway 会把 Provider 抛出的异常转换为结构化 `TECHNICAL_FAILURE`，因此生产调用链也会留下阻断记录。
需要再次尝试时，应先查询业务系统或人工核对，再创建新的操作 key，而不是盲目复用原 key。

## 5. 为什么需要租约

如果只有 `IN_PROGRESS`，执行实例在调用 Provider 前崩溃后，这个 key 会永远不能再次执行。

租约给执行权设置有效期。owner 从执行 Provider、编码结果直到完成记录落库的整个临界区，都会
每隔约三分之一租约自动续期：

```text
T0      实例 A 抢占，lease_until=T0+60s
T20s    A 仍在执行，心跳把 lease_until 延长到 T80s
T40s    A 仍在执行，再延长到 T100s
T45s    实例 B 只能等待，不能接管
T50s    若 A 崩溃，心跳停止
T100s   最后一次租约过期
T101s   一个实例通过带旧 owner/lease 条件的 UPDATE 接管
```

接管 SQL 必须同时检查：

```text
storage_key_hash
request_fingerprint_hash
status=IN_PROGRESS
旧 owner_token
旧 lease_until
```

如果只检查 key，两个接管者仍可能同时认为自己成功。

当前默认租约为 60 秒，而 Capability 默认执行 deadline 为 30 秒。心跳主要保护慢调用和短时抖动，
不能消除进程崩溃、长时间 GC、数据库长时间不可达和节点时钟漂移带来的所有不确定性。真正的长任务
仍应改成有任务 ID、可查询状态的异步模型。

## 6. 数据库表

jdbc 模式会创建 `cs_capability_idempotency`：

```text
storage_key_hash          CHAR(64) PRIMARY KEY
request_fingerprint_hash  CHAR(64)
status                    VARCHAR(24)
owner_token               VARCHAR(64)
lease_until               BIGINT
result_json               LONGTEXT NULL
result_schema_version     INT NULL
created_at                BIGINT
updated_at                BIGINT
```

设计说明：

- 不保存 storageKey 原文，减少 tenant、用户和会话标识的重复暴露。
- 请求参数先转为递归排序的 canonical JSON，再保存 SHA-256，不把完整参数复制到幂等表。
- `owner_token` 只标识一次执行资格，不是用户身份。
- `updated_at` 建有索引，后续可以按时间清理过期记录。
- `result_schema_version` 与 JSON 内部版本交叉校验，防止列和内容不一致。

## 7. 结果为什么保存 JSON，而不是 Java 类名

`CapabilityResult.data` 可能是：

- `ReturnToolData`。
- `HandoffTicketSummary`。
- 普通 Map、List、字符串或数字。
- 未来由远程 MCP 返回的 JSON 对象。

如果把 Java 类名写入数据库，会产生三个问题：

1. 重命名包或 DTO 后旧数据无法读取。
2. 本地 Provider 和远程 MCP 的数据表示不一致。
3. 开启 Jackson Default Typing 会扩大不安全多态反序列化风险。

因此 `CapabilityResultCodec` 只保存稳定 JSON。重启后对象通常恢复为 `Map` 或 `List`，由具体契约
在自己的边界转换。例如 `ReturnToolData.fromMap(...)` 同时接受原 DTO 和 JSON Map：

```text
Gateway 回放普通 Map
        |
        v
ReturnApplyWorkflow
        |
        v
ReturnToolData.fromMap
```

Gateway 不包含 `return.apply` 的字段判断。以后新增副作用 Capability 时，应让对应输出契约提供
类似的显式恢复方法，而不是继续向 Gateway 添加业务 switch。

## 8. schemaVersion

幂等结果也有独立版本：

```text
Session revision                  -> 会话 CAS 版本
ConversationState schemaVersion  -> 会话 JSON 结构版本
WorkflowState schemaVersion      -> Workflow payload 结构版本
CapabilityResult schemaVersion   -> 幂等回放结果结构版本
```

这四个版本解决不同问题，不能混用。

当前 CapabilityResult schemaVersion 为 1。遇到未知未来版本时，Codec 会失败关闭，不会猜测字段含义。
注册式结果 Migrator 目前没有实现；在真正修改 v1 结构前必须先补迁移链。

## 9. 序列化失败为什么不能释放 claim

考虑以下情况：

```text
Provider 已成功创建工单
        |
        v
result.data 无法序列化
```

如果此时删除 claim，下一次请求会再次创建工单。为避免重复副作用，Store 会保存一个可序列化的
兜底技术结果：

```text
IDEMPOTENCY_RESULT_SERIALIZATION_FAILED
```

第一次调用仍收到 Provider 的原结果；以后回放会提示结果无法恢复，并继续阻止 Provider 再次执行。
这比“为了返回漂亮数据而重复写入业务系统”更安全。

## 10. 配置

默认配置位于 `application.yml`：

```yaml
smartcs:
  capability:
    idempotency:
      lease-ms: 60000
      wait-timeout-ms: 30000
      poll-interval-ms: 50
```

对应环境变量：

```text
SMARTCS_IDEMPOTENCY_LEASE_MS
SMARTCS_IDEMPOTENCY_WAIT_TIMEOUT_MS
SMARTCS_IDEMPOTENCY_POLL_INTERVAL_MS
```

参数含义：

| 参数 | 含义 | 设置过小的风险 |
| --- | --- | --- |
| `lease-ms` | 执行权多久后允许接管 | 正常 Provider 尚未完成就被接管 |
| `wait-timeout-ms` | 并发请求最多等待多久 | 过早返回“仍在执行” |
| `poll-interval-ms` | 检查完成记录的间隔 | 过于频繁地查询数据库 |

生产配置应保证 `lease-ms` 大于最长同步 Provider deadline，并为网络抖动留出余量。

JDBC 客户端还统一设置了三层数据库超时：

```text
SMARTCS_JDBC_CONNECT_TIMEOUT_MS=3000
SMARTCS_JDBC_SOCKET_TIMEOUT_MS=10000
SMARTCS_JDBC_QUERY_TIMEOUT_SECONDS=5
```

一次 JDBC 调用的近似上界是 `connectTimeout + max(socketTimeout, queryTimeout)`，还要额外考虑线程
调度、GC 和数据库端取消延迟。租约时间必须显著大于这个上界，否则数据库暂时变慢时，owner 可能
还在等待续租 SQL，另一实例却已经认为租约过期。超时只能给阻塞设置上限，不能让 Provider 副作用
和幂等记录自动变成同一个事务。

## 11. Gateway 对外错误码

| 错误码 | 含义 | 是否调用 Provider |
| --- | --- | --- |
| `IDEMPOTENCY_KEY_REQUIRED` | 副作用调用没有 key | 否 |
| `IDEMPOTENCY_KEY_CONFLICT` | 同 key 被用于不同参数 | 否 |
| `IDEMPOTENCY_EXECUTION_IN_PROGRESS` | 等待超时，另一个实例仍在执行 | 否 |
| `IDEMPOTENCY_STORE_ERROR` | 幂等存储不可用或结果损坏 | 否 |

这些错误不会包含 JDBC URL、用户名、SQL 异常原文或堆栈。

## 12. 测试覆盖

本批新增和扩展的测试覆盖：

| 场景 | 验证点 |
| --- | --- |
| 内存并发 | 同 key 的两个线程只调用一次 Provider |
| JDBC Store 重建 | 完成结果跨实例、跨重建回放 |
| 两个 JDBC Store 并发 | 数据库唯一键只允许一个执行者 |
| 跨用户相同 key | tenant/user/conversation 作用域阻止越权回放 |
| 嵌套 JSON 参数 | canonical JSON 不受嵌套 Map 插入顺序影响 |
| 参数冲突 | 相同 key 不同 fingerprint 被拒绝 |
| 技术失败结果未知 | 同 key 稳定回放，不自动再次调用 Provider |
| 过期租约 | 崩溃 owner 的记录可以原子接管 |
| 慢 Provider | 跨过初始租约仍持续续租，不发生第二次执行 |
| 慢完成落库 | Provider 返回后到 `COMPLETED` 更新结束前仍持续续租 |
| 插入/释放竞争 | 前 owner 立即释放后，新请求重新竞争，不误报数据库故障 |
| JSON Codec | 成功/业务拒绝结果可往返 |
| 未来 schema | 未知版本失败关闭 |
| DTO 恢复 | ReturnApplyWorkflow 可以消费回放后的 Map |
| Store 不可用 | Gateway 失败关闭且 Provider 调用次数为 0 |

第三批完成时的验证结果：

```text
mvn test
Tests run: 297, Failures: 0, Errors: 0, Skipped: 0
```

## 13. 当前限制

1. CI 仍未使用真实 MySQL/Testcontainers，本批通过带原子条件的 JDBC 测试替身验证 SQL 契约。
2. `JdbcDatabaseClient` 每次查询都通过 DriverManager 新建连接，没有连接池或事务；第四批已补充连接、
   Socket 和 SQL 执行超时，详见第 45 篇。
3. 表结构仍由代码执行 `CREATE TABLE IF NOT EXISTS`，没有 Flyway/Liquibase。
4. 完成记录还没有 TTL 清理任务，长期运行后表会持续增长。
5. canonical JSON 只面向 Capability 的 JSON 兼容参数；自定义二进制对象或不可序列化对象会失败关闭。
6. 续租使用应用节点时钟和后台线程；显式数据库超时能限制阻塞时长，但长时间 GC、数据库阻塞或
   严重时钟漂移仍可能造成租约不确定。
7. Provider 副作用与幂等完成记录不是同一个数据库事务，崩溃窗口仍可能导致再次调用。
8. `ReturnApplicationService` 的订单锁仍是进程内锁，退货表也应增加 `order_id` 唯一约束。
9. memory 模式只适合单实例 Demo，不能因为实现了同一 SPI 就把它当作分布式方案。
10. readiness 检查已在下一批完成，详见 `客服Agent-45-Liveness与Readiness健康探针说明.md`。

## 14. 下一批建议

后续按风险优先级继续：

1. 使用 Testcontainers MySQL 验证真实唯一键、方言和并发行为。
2. 为退货业务增加数据库唯一约束，并评估与幂等记录共事务。
3. 为外部 MCP/HTTP Provider 统一透传 idempotencyKey。
4. 实现 WorkflowStateMigrator 注册链。
5. 增加幂等完成记录 TTL 与安全清理任务。

本批之后，多实例已经拥有明确的“先抢占、再执行、后回放”边界。下一阶段要继续把学习型 JDBC
实现补成可观测、可迁移、可用真实数据库验证的生产基础设施。
