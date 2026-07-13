# 客服 Agent PendingHandoff 租户隔离与复合键说明

## 1. 本批解决了什么问题

第二十批要开放人工转接复核管理接口，但原有 `PendingHandoff` 数据没有 `tenantId`。如果直接增加
ADMIN 列表和详情接口，即使 Controller 根据 JWT 过滤，也无法判断数据库里的记录属于哪个租户。

本小批次先完成安全地基：

- `PendingHandoffCommand` 与 `PendingHandoffRecord` 新增 `tenantId`；
- 新增不可变复合键 `PendingHandoffKey(tenantId, idempotencyKey)`；
- memory Store 的记录和租约 Map 都使用复合键；
- JDBC 主表、成功结果表和租约表都使用租户复合唯一键；
- 所有单条查询、CAS 更新、成功确认和租约领取都必须携带复合键；
- 后台 Recovery Worker 从记录中恢复原 tenantId；
- 售后 HTTP Client 使用当前执行上下文的 tenantId 发送 `X-Tenant-Id`；
- 外部幂等键哈希加入 tenantId，避免不同租户生成相同远端 key；
- 提供已有 MySQL 数据卷的显式迁移脚本。

这一步完成后，后续 ADMIN API 才有可靠的数据归属基础。

## 2. 为什么要新增 PendingHandoffKey

如果接口仍然长这样：

```java
findByIdempotencyKey(String idempotencyKey)
```

调用者很容易忘记租户过滤。即使把方法改成两个 `String` 参数，也可能把顺序写反，而编译器无法发现。

现在统一使用：

```java
public record PendingHandoffKey(String tenantId, String idempotencyKey) {
}
```

Store 的单条操作变成：

```java
Optional<PendingHandoffRecord> findByKey(PendingHandoffKey key);

PendingHandoffRecord confirm(
        PendingHandoffKey key,
        AfterSalesTicketCreateResult result,
        long expectedVersion
);
```

这样有三个好处：

1. 方法签名强迫调用者带租户；
2. `Map` 可以直接用复合键，不需要手工拼接字符串；
3. JDBC 的每条单记录 SQL 都能从同一个对象取得 `tenant_id + idempotency_key`。

## 3. 完整数据流

```text
JWT tenant_id
  -> AuthenticatedPrincipal
  -> ChatRequest / ExecutionContext
  -> LocalHandoffCapabilityProvider
  -> HandoffTools.createHandoffTicketForTenant(...)
  -> PendingHandoffCommand.tenantId
  -> PendingHandoffRecord.tenantId
  -> PendingHandoffKey
       -> InMemory records / claims
       -> JDBC 主表 / result 表 / claim 表
  -> Recovery Worker
  -> AfterSalesTicketClient.createTicket(tenantId, request)
  -> HTTP X-Tenant-Id
```

`LocalHandoffCapabilityProvider` 还会检查：已认证 Principal 的 tenantId 必须与 ExecutionContext 的
tenantId 一致。如果未来新增入口时错误组合了两个不同租户的上下文，会 fail-closed 返回
`TENANT_CONTEXT_MISMATCH`，而不是继续创建工单。

## 4. 为什么三张表都要改

主表的新约束是：

```text
UNIQUE (tenant_id, idempotency_key)
```

但只修改主表仍然不安全：

- `cs_pending_handoff_result` 若仍以裸 key 为主键，租户 B 可能回放租户 A 的成功结果；
- `cs_pending_handoff_claim` 若仍以裸 key 为主键，租户 A 的 Worker 租约会阻塞租户 B；
- 释放或接管租约时若不带 tenant，旧 Worker 可能删除另一个租户的租约。

因此三个约束必须一起改：

```text
cs_pending_handoff
  PRIMARY KEY (outbox_id)
  UNIQUE (tenant_id, idempotency_key)

cs_pending_handoff_result
  PRIMARY KEY (tenant_id, idempotency_key)

cs_pending_handoff_claim
  PRIMARY KEY (tenant_id, idempotency_key)
```

## 5. 为什么售后 HTTP 租户不能继续用全局配置

旧实现使用 `SMARTCS_AFTER_SALES_TENANT_ID`，意味着一个 Agent 进程只能固定写入一个售后租户。
当 Agent 的 JWT 已经支持多租户时，这会造成“本地 Outbox 属于租户 A，远端工单却写入 demo 租户”的
严重不一致。

现在 `AfterSalesTicketClient` 的 Port 明确接收 tenantId：

```java
AfterSalesTicketCreateResult createTicket(
        String tenantId,
        AfterSalesTicketCreateRequest request
);
```

HTTP Adapter 只把这个可信值写入 `X-Tenant-Id`。全局租户配置已删除；base URL、Agent 服务身份和超时
仍然保留为部署配置。

## 6. 已有 MySQL 数据卷如何升级

新建数据库会由 `JdbcPendingHandoffStore` 创建带 tenant_id 的新表。已有数据库执行
`CREATE TABLE IF NOT EXISTS` 不会自动增加列，必须先运行：

[`客服Agent-62-PendingHandoff租户隔离迁移.sql`](客服Agent-62-PendingHandoff租户隔离迁移.sql)

安全执行顺序：

1. 停止所有 Agent 实例和 Recovery Worker；
2. 备份数据库；
3. 确认历史 Outbox 数据只属于哪一个租户；
4. 把脚本中的 `CHANGE_ME` 改成该租户 ID；
5. 使用有 `ALTER TABLE` 权限的数据库账号执行脚本；
6. 确认脚本末尾三个验收查询都返回 0；
7. 启动新版本并执行真实 MySQL 验收。

为什么脚本不擅自把历史数据都写成 `default`？旧数据没有租户证据，程序不能替业务做这个决定。
如果历史记录来自多个租户，应先人工建立映射并分批回填，不能直接运行单租户脚本。

为什么不把 `ALTER TABLE` 放进 Store 构造函数？多实例会同时启动，构造函数没有可靠的迁移锁；而且
DDL 会自动提交，失败后不等同于普通事务回滚。当前项目尚未引入 Flyway，因此先提供显式、可审核的
停机迁移脚本。未来进入滚动发布阶段，应引入版本化迁移工具并拆分 expand/contract 两次发布。

## 7. 自动化测试证据

本批测试覆盖：

- 同一个裸 idempotencyKey 可在 tenant A 和 tenant B 各保存一条记录；
- 一个租户确认成功不会改变另一个租户的记录；
- memory/JDBC 两种 Store 使用相同复合键语义；
- Recovery Worker 使用记录里的 tenantId 调用售后 Client；
- HTTP 请求头使用方法参数里的 tenantId，而不是静态配置；
- 相同用户、会话、原因和操作在不同租户生成不同远端幂等键；
- Principal 与 ExecutionContext 租户不一致时不调用 HandoffTools；
- 未认证身份不能执行 `handoff.request` 副作用；
- after-sales-http 缺少 Client 或从旧无租户入口调用时会失败关闭，不会伪造 memory 成功；
- 现有 Outbox 准备、结果未知、成功回放、租约接管和有限重试仍然通过。

验证命令：

```bash
bash mvnw -q test
```

本次实际结果为 428 项默认测试全部通过。另使用临时 MySQL 8.4 容器完成：

- 从旧版三张表和历史主记录、结果、Claim 执行迁移；
- 连续执行迁移脚本两次，验证幂等；
- 验证三个复合约束的列顺序；
- 验证两个租户可保存相同裸 key、不同结果和不同 Claim；
- 运行扩展后的 `RealMySqlPersistenceIT`，验证真实 JDBC 绑定、CAS、结果和租约隔离。

验收后临时容器已停止并删除。

## 8. 当前边界与下一步

本小批次只建立租户安全地基，还没有开放 ADMIN HTTP 接口。后续按顺序继续：

1. 增加 tenant + outboxId 的详情查询和游标列表；
2. 增加人工 retry、confirm、close 的版本 CAS 状态机；
3. 增加追加式人工操作审计；
4. 增加 ROLE_ADMIN Controller、脱敏 DTO、稳定 404/409；
5. 增加可替换的售后结果查询 Port。

另外，迁移审计发现现有机器租约完成操作只验证 owner 和 version，尚未验证“租约在提交时仍未过期”。
这不属于本小批次的 tenant 字段贯通，但会在后续状态机改造时一起收紧。

## 9. 初学者建议阅读顺序

1. `PendingHandoffKey`：先理解为什么复合键比两个 String 更安全；
2. `PendingHandoffCommand` 与 `PendingHandoffRecord`：看 tenantId 如何成为持久化数据；
3. `InMemoryPendingHandoffStore`：看 Map 如何用复合键隔离；
4. `PendingHandoffStore`：看上层依赖的稳定 Port；
5. `JdbcPendingHandoffStore`：把复合键对应到 SQL WHERE 和数据库约束；
6. `LocalHandoffCapabilityProvider` 与 `HandoffTools`：看可信上下文如何进入 Outbox；
7. `PendingHandoffRecoveryWorker`：看重启后的租户如何继续传给远端；
8. `HttpAfterSalesTicketClient`：最后看 tenantId 如何变成 HTTP Header。
