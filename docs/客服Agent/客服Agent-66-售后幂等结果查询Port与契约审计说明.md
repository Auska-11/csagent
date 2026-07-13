# 客服 Agent 20.5：售后幂等结果查询 Port 与契约审计说明

## 1. 先说结论

20.5 没有假装接入一个不存在的远端查询接口。对独立 `after-sales-sla-platform` 项目做代码级审计后，结论是：

- 售后平台会保存 `(tenantId, Idempotency-Key) -> ticketId` 映射；
- 使用相同 key 和相同请求再次调用 `POST /api/tickets`，可以返回原工单；
- 但平台没有公开“按 Idempotency-Key 只读查询工单”的 HTTP API；
- Agent 使用的 `AGENT` 角色只能创建工单，不能调用管理列表或按 ID 详情接口；
- 因此客服 Agent 当前无法得到权威的 FOUND / NOT_FOUND 查询结论。

本批采取的正确做法是：

1. 建立可替换的 `AfterSalesTicketResultLookup` 出站 Port；
2. 用三态结果严格区分 `FOUND`、权威 `NOT_FOUND` 和 `UNAVAILABLE`；
3. 默认装配 `UnsupportedAfterSalesTicketResultLookup`，诚实返回 `QUERY_NOT_SUPPORTED`；
4. 新增 ADMIN `reconcile` 用例，把 Port 真正接入应用链路，而不是留下无人调用的空抽象；
5. 只有 FOUND 才能确认主记录并写审计；NOT_FOUND 和 UNAVAILABLE 都保持零写入；
6. 未来下游补齐查询契约后，只新增 HTTP Adapter 并切换配置，不修改 Controller、应用服务、状态机或 Store。

## 2. 外部平台审计证据

本次审计对象：

```text
项目：after-sales-sla-platform
审计提交：cee9045a117414299bec4a2d69b24aa1dcf6b502
审计日期：2026-07-13
```

### 2.1 它确实支持创建幂等

`IdempotencyRecord` 使用租户与幂等键复合唯一约束，并保存对应 `resourceId`：

```text
tenant_id + idempotency_key -> resource_id(ticketId)
```

`IdempotencyRecordRepository` 也有内部方法：

```java
Optional<IdempotencyRecord> findByTenantIdAndIdempotencyKey(
        String tenantId,
        String idempotencyKey
);
```

`TicketService#create` 会先查 Redis 和数据库幂等记录；并发唯一键冲突后还会回查赢家结果。因此“相同
POST 重试不重复建单”有真实代码与数据库约束支持。

### 2.2 内部 Repository 方法不等于外部 API

`TicketController` 公开的相关接口只有：

| 接口 | 角色 | 能否按幂等键核对 |
| --- | --- | --- |
| `POST /api/tickets` | CUSTOMER、AGENT、SUPPORT、ADMIN | 不能；这是创建/幂等重放，不是只读查询 |
| `GET /api/tickets` | SUPPORT、TECH、ADMIN | 不能；只支持 status、keyword、page、size |
| `GET /api/tickets/{id}` | CUSTOMER、SUPPORT、TECH、ADMIN | 不能；调用方必须已经知道 ticketId |
| `GET /api/tickets/mine` | CUSTOMER | 不能；按当前客户身份查询，不接受幂等键 |

没有类似下面的路由：

```text
GET /api/tickets/by-idempotency-key
GET /api/tickets/idempotency/{key}
```

而客服 Agent 的服务身份是 `AGENT`，也不在列表和详情的查询角色中。不能绕开权限，改用管理端搜索接口
模糊匹配标题或客户信息；那既不是权威映射，也可能越权读取其他工单。

### 2.3 为什么不能把重复 POST 当成查询

重复 POST 当前确实会被下游幂等层合并，但它仍然是一个副作用命令：

```text
已有映射 -> 返回原工单
没有映射 -> 创建一张新工单
```

所以它无法回答“远端是否已经存在”：当答案为否时，调用本身会改变答案。20.5 的查询 Port 明确要求只读，
不能偷偷调用创建接口后再把结果伪装成“查询命中”。Recovery Worker 可以在业务明确允许时继续使用原 key
做有限补建，但 ADMIN reconcile 的证据必须来自真正的只读查询。

## 3. 为什么结果必须是三态

Port 返回 `AfterSalesTicketLookupResult`，状态如下：

| outcome | 含义 | 是否携带 ticket | 是否允许确认 |
| --- | --- | --- | --- |
| `FOUND` | 权威查询命中当前租户与幂等键 | 必须携带 | 允许 |
| `NOT_FOUND` | 查询成功，权威确认不存在 | 不允许携带 | 不允许 |
| `UNAVAILABLE` | 协议不支持、依赖失败或响应无效 | 不允许携带 | 不允许 |

如果只返回 `Optional<AfterSalesTicketCreateResult>`：

```text
Optional.empty()
  = 远端明确没有？
  = 网络超时？
  = 没有查询接口？
  = JSON 解析失败？
```

上层无法安全决定。最危险的错误是把后三种未知当成 NOT_FOUND，然后重新创建或关闭原记录。

UNAVAILABLE 还带一个不含敏感数据的结构化原因：

| reason | 使用场景 |
| --- | --- |
| `QUERY_NOT_SUPPORTED` | 下游没有公开查询协议，本项目当前默认值 |
| `REMOTE_UNAVAILABLE` | 未来 HTTP 查询超时、连接失败或 5xx |
| `INVALID_RESPONSE` | 下游返回缺字段、非法时间或不可信结构 |

原因中不保存 tenantId、idempotencyKey、URL、凭据或异常原文。`AfterSalesTicketLookupResult#toString()` 也
不会打印 FOUND 工单内容，避免未来调试日志扩大敏感数据范围。

## 4. Port 与 Adapter 怎样解耦

```text
PendingHandoffAdminService
  -> AfterSalesTicketResultLookup                 # 稳定出站 Port
       -> UnsupportedAfterSalesTicketResultLookup # 当前 Adapter
       -> HttpAfterSalesTicketResultLookup        # 下游补协议后再新增
```

Port 方法只接收 `PendingHandoffKey`：

```java
AfterSalesTicketLookupResult findByIdempotencyKey(
        PendingHandoffKey key
);
```

`PendingHandoffKey` 同时包含 tenantId 与 idempotencyKey。这样 Adapter 无法只拿一个裸 key 忘记租户条件。
key 来自当前租户内查到的 Outbox Record，不来自 HTTP 请求体或模型参数。

当前 Adapter 使用条件装配：

```text
smartcs.handoff.after-sales.result-query-mode=unavailable
```

未来有真实协议时，新 Adapter 使用 `havingValue="http"`，并把配置切到 `http`。如果提前配置一个没有实现的
模式，Spring 会因缺少 Port Bean 启动失败；这比运行时静默退回错误行为更安全。

## 5. ADMIN reconcile 接口

20.5 在原 ADMIN API 上新增第七个入口：

```text
POST /api/admin/pending-handoffs/{outboxId}/reconcile
```

请求体：

```json
{
  "expectedVersion": 3,
  "reasonCode": "SERVER_LOOKUP_REQUESTED",
  "decisionNote": "请求服务端按原始幂等键核对"
}
```

请求体不能提供：

- tenantId；
- actorId；
- idempotencyKey；
- ticket result；
- evidenceSource。

真实调用顺序：

```text
校验 JWT 与 ROLE_ADMIN
  -> 用 Principal.tenantId + URL outboxId 查询主记录
  -> 比较 expectedVersion
  -> 确认状态仍为 NEEDS_MANUAL_REVIEW
  -> 从主记录内部取得 PendingHandoffKey
  -> 调用 AfterSalesTicketResultLookup
       FOUND
         -> 校验 ticketId/status/createdAt
         -> evidence=AFTER_SALES_IDEMPOTENCY_QUERY
         -> PendingHandoffManualDecisionStore.confirm
         -> 主记录 + 成功结果 + 审计原子提交
       NOT_FOUND
         -> 404，零状态写入，零审计写入
       UNAVAILABLE
         -> 503，零状态写入，零审计写入
```

查询前做一次版本和状态检查，是为了避免明显过期页面仍调用外部系统。远端查询完成后，Store 还会再执行
原子 version CAS；因为查询期间另一个管理员仍可能完成操作。前检查减少无意义调用，后 CAS 保证并发正确性。

## 6. HTTP 结果与状态变化

| 查询结果 | HTTP | errorCode / 响应 | 主记录 | 审计 |
| --- | --- | --- | --- | --- |
| FOUND | 200 | `PendingHandoffAdminDecisionResponse` | `NEEDS_MANUAL_REVIEW -> CONFIRMED` | 追加 CONFIRM，证据为查询 |
| NOT_FOUND | 404 | `PENDING_HANDOFF_REMOTE_TICKET_NOT_FOUND` | 不变 | 不追加 |
| UNAVAILABLE | 503 | `PENDING_HANDOFF_RESULT_QUERY_UNAVAILABLE` | 不变 | 不追加 |
| stale version | 409 | `PENDING_HANDOFF_VERSION_CONFLICT` | 不变 | 不追加 |
| 非待复核状态 | 409 | `PENDING_HANDOFF_STATE_CONFLICT` | 不变 | 不追加 |

本地 Outbox 不存在仍使用 `PENDING_HANDOFF_NOT_FOUND`，与“本地记录存在但远端查询明确没有结果”是不同
错误码。UNAVAILABLE 的 HTTP 响应不回显内部 reason，避免未来把 URL、依赖细节或配置状态扩散到客户端；
503 和稳定 errorCode 已经明确表达“当前没有权威答案”。

当前默认模式下，reconcile 一定返回 503。这不是接口坏了，而是系统诚实表达外部契约缺失。管理员仍可以：

- 在受信任售后后台人工核对后调用 `/confirm`，审计证据为 `ADMIN_PROVIDED`；
- 在业务判断允许时调用 `/retry` 重新排队；
- 明确无需工单时调用 `/close`。

## 7. FOUND 为什么还要校验远端结果

即使 Adapter 返回 FOUND，应用服务仍会检查：

- ticketId 必填、最多 128 字符、只允许结构化标识字符；
- status 必填、最多 64 字符、只允许结构化状态字符；
- createdAt 必填、最多 128 字符且能被 `Instant.parse`；
- 时间统一规范为 UTC 字符串。

不合法结果会转成 `UNAVAILABLE + INVALID_RESPONSE` 对应的 503，而不是 400。原因是管理员请求没有错，错误
来自下游响应。只有验证后的 FOUND 才能进入 `PendingHandoffManualDecisionStore.confirm()`。

## 8. 下游未来需要补什么契约

建议独立售后平台增加一个最小只读接口，例如：

```text
GET /api/tickets/by-idempotency-key
X-Tenant-Id: <trusted tenant>
X-User-Id: agent-service
X-User-Role: AGENT
Idempotency-Key: <original key>
```

最低要求：

1. 必须按认证租户与幂等键精确查询，不能只按全局 key；
2. 必须允许受控 AGENT 服务身份调用，但不能因此放开工单列表；
3. 200 返回最小 `id/status/createdAt`，不返回客户描述等无关敏感字段；
4. 404 必须只表示权威不存在；数据库或 Redis 故障不能伪装成 404；
5. 401、403、429、5xx 和超时语义稳定；
6. 查询本身不得创建工单、刷新业务状态或产生其他副作用；
7. 增加跨租户同 key、存在、不存在、权限和数据库故障测试；
8. 契约稳定后再在本项目新增 `HttpAfterSalesTicketResultLookup`，使用现有连接/读取超时。

完成这些条件后，切换为：

```text
SMARTCS_AFTER_SALES_RESULT_QUERY_MODE=http
```

Admin Controller、Service、证据枚举和 Memory/JDBC 状态机都无需增加售后平台特判，这就是本批扩展性改造
真正提供的价值。

## 9. 测试重点

20.5 自动化测试覆盖：

- 三态工厂方法和不可能字段组合；
- FOUND 工单不会被默认 `toString()` 打印；
- 默认 Adapter 返回 `UNAVAILABLE/QUERY_NOT_SUPPORTED`，绝不返回 NOT_FOUND；
- 配置默认值、大小写规范化和空值拒绝；
- FOUND 使用可信租户与 Outbox 原 key，并规范 UTC 时间；
- FOUND 审计证据固定为 `AFTER_SALES_IDEMPOTENCY_QUERY`；
- NOT_FOUND 与 UNAVAILABLE 抛出不同安全异常，且均不调用 Decision Store；
- stale version 和非法状态在调用查询 Port 前失败；
- 非法远端结果映射为 503，而不是管理员 400；
- 创建接口的 success 外壳缺少 id/status/createdAt 时 fail-closed，不再伪造 AS-UNKNOWN 或本机时间；
- Controller 忽略客户端伪造的 tenant、actor、ticket 与 evidence；
- 真实 JWT + Spring + Memory Store 默认 reconcile 返回 503，状态、版本和审计均不变化；
- 404、503 的稳定 HTTP 错误码和安全 message。

最终本地验收：

```text
默认测试：510 项，0 失败、0 错误、0 跳过
跳过测试 JAR 打包：通过
```

20.5 没有修改 PendingHandoff 的 JDBC SQL、表结构或事务实现，所以没有重复运行真实 MySQL 来制造一份
与本批无关的数据库证据。20.3 的真实事务原子性验收仍然有效，完整真实 MySQL 与 Docker 总验收留在
20.6 统一执行。

## 10. 当前边界

20.5 完成了可替换查询架构和 fail-closed 应用链路，但没有宣称已经实现远端自动查询：

- 当前没有 HTTP 查询 Adapter，因为下游没有对应接口；
- 当前默认 reconcile 是 503，不会自动确认；
- 没有为失败的 reconcile 尝试追加状态迁移审计；现有审计只记录真正成功的人工状态变化；
- 没有修改外部售后项目，它是独立仓库和独立发布边界；
- 没有把管理列表或数据库直连当成临时后门；
- 20.6 仍需更新 README、核心架构图、简历手册，并执行真实 MySQL 与 Docker 总验收。

## 11. 初学者阅读顺序

1. `AfterSalesTicketLookupOutcome`：先理解三种结论为什么不能合并；
2. `AfterSalesTicketLookupResult`：看构造器怎样拒绝矛盾字段组合；
3. `AfterSalesTicketResultLookup`：理解 Port 只表达业务契约，不写 HTTP；
4. `UnsupportedAfterSalesTicketResultLookup`：看当前 Adapter 为什么只返回 unavailable；
5. `PendingHandoffAdminController#reconcile`：看新增 HTTP 入口；
6. `PendingHandoffAdminService#reconcile`：看版本预检、三态分支和证据控制；
7. `GlobalExceptionHandler`：看 NOT_FOUND 和 UNAVAILABLE 怎样成为不同 HTTP 语义；
8. `PendingHandoffAdminServiceTest`：看 FOUND 的未来 Adapter 契约；
9. `PendingHandoffAdminIntegrationTest`：看当前默认 Adapter 的真实零写入行为；
10. 最后对照售后平台 `TicketController` 与 `IdempotencyRecordRepository`，理解“内部能查”和“外部可调用”
    为什么不是一回事。

核心原则：

> 结果未知不是结果不存在；只有权威只读查询得到的 FOUND 才能成为系统自动确认成功的证据。
