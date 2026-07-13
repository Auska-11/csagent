# 客服 Agent 20.4：ADMIN 人工复核 API 说明

## 1. 这一批解决什么问题

20.1 到 20.3 已经依次补齐 PendingHandoff 的租户隔离、人工复核状态机和追加式审计，但这些能力当时只
存在于领域 Port 与 Memory/JDBC Adapter 中，管理员还没有一个受保护的 HTTP 入口可以使用。

20.4 增加完整的 ADMIN 人工复核 API，当前能够：

- 按状态分页查询当前 JWT 租户内的 PendingHandoff；
- 查询单条脱敏详情；
- 查询追加式人工处置审计时间线；
- 对 `NEEDS_MANUAL_REVIEW` 记录执行重新排队、人工确认或人工关闭；
- 用 `expectedVersion` 防止旧页面覆盖并发决定；
- 从 JWT 注入 tenantId 和 actorId，不相信请求体里的同名字段；
- 只返回专用最小 DTO，不直接序列化包含敏感信息的 `PendingHandoffRecord`；
- 为参数、游标、找不到、版本冲突和状态冲突返回稳定 HTTP 错误码。

这一批提供的是后端 API，不包含管理端前端页面。售后平台按 `Idempotency-Key` 自动查询结果属于 20.5，
不能把当前的“管理员人工确认”描述成系统已经自动核对远端结果。

## 2. 请求经过哪些层

```text
ADMIN HTTP 请求
  -> Spring Security Resource Server
       -> 校验 JWT 签名、过期时间、issuer、audience、subject 和 tenant_id
       -> JwtPrincipalConverter 生成 AuthenticatedPrincipal
       -> SecurityFilterChain 要求 ROLE_ADMIN
  -> PendingHandoffAdminController
       -> 只做 URL、查询参数和 JSON 请求体绑定
  -> PendingHandoffAdminService
       -> 再次防御性校验 ADMIN
       -> 从 Principal 取得 tenantId 与 actorId
       -> 校验 limit、status、游标、版本和人工确认结果
       -> 调用窄 Port，不依赖 Memory 或 JDBC 具体实现
  -> PendingHandoffReviewStore / PendingHandoffManualDecisionStore /
     PendingHandoffReviewAuditStore
       -> Memory：不可变 StoredEntry + Map CAS
       -> JDBC：租户条件 + 行锁 + version CAS + 同事务审计
  -> PendingHandoffAdminResponseMapper
       -> 字段白名单、自由文本脱敏、内部调度哨兵隐藏
  -> 安全 JSON 响应
```

Controller 和 SecurityFilterChain 都检查权限并不是重复浪费。Filter 是公网第一道边界，Service 的检查保证
以后即使增加非 HTTP 调用入口，也不能绕过 ADMIN 约束直接调用应用服务。

## 3. API 清单

所有接口都位于 `/api/admin/pending-handoffs`，并要求有效的 `ROLE_ADMIN` Bearer JWT。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` | `/api/admin/pending-handoffs` | 按租户和状态查询列表 |
| `GET` | `/api/admin/pending-handoffs/{outboxId}` | 查询一条脱敏详情 |
| `GET` | `/api/admin/pending-handoffs/{outboxId}/audit-events` | 查询人工处置审计时间线 |
| `POST` | `/api/admin/pending-handoffs/{outboxId}/retry` | 重新排队，等待 Worker 再处理 |
| `POST` | `/api/admin/pending-handoffs/{outboxId}/confirm` | 录入管理员已核对的远端工单结果 |
| `POST` | `/api/admin/pending-handoffs/{outboxId}/close` | 明确关闭，不再自动恢复 |

列表查询参数：

| 参数 | 默认值 | 边界 |
| --- | --- | --- |
| `status` | `NEEDS_MANUAL_REVIEW` | `PendingHandoffStatus` 枚举值，不区分大小写 |
| `cursor` | 无 | 上一页返回的不透明 Base64URL 文本 |
| `limit` | `20` | 只能是 1 到 100 |

审计查询支持同样的 `cursor` 和 `limit`，但审计游标与目标 `outboxId` 绑定，不能复制到另一条记录使用。

## 4. 身份为什么不能由请求体提供

一次人工决定真正可信的身份字段只有两项来源：

```text
tenantId = AuthenticatedPrincipal.getTenantId()
actorId  = AuthenticatedPrincipal.getUserId()
```

retry、confirm、close 的请求 DTO 都没有 `tenantId`、`actorId`、`authorityUsed` 或 `evidenceSource` 字段。
即使客户端额外提交这些 JSON 字段，它们也不会进入领域命令。这样能防止以下伪造：

```json
{
  "tenantId": "another-tenant",
  "actorId": "another-admin",
  "authorityUsed": "SYSTEM",
  "evidenceSource": "AFTER_SALES_IDEMPOTENCY_QUERY"
}
```

列表、详情、写操作和审计查询都会把 Principal 的 tenantId 传给 Store。跨租户访问与真正不存在使用完全
相同的 `404 PENDING_HANDOFF_NOT_FOUND`，调用者不能借错误差异探测其他租户是否拥有某个 outboxId。

## 5. 三种人工决定

### 5.1 retry：重新排队

```json
{
  "expectedVersion": 3,
  "reasonCode": "MANUAL_RETRY_APPROVED",
  "decisionNote": "已检查平台状态，可以重新尝试"
}
```

状态变化：

```text
NEEDS_MANUAL_REVIEW -> RETRY_SCHEDULED
```

它只设置新的调度时间，不在 ADMIN 请求线程直接调用售后平台，也不增加代表真实远端尝试次数的
`attemptCount`。证据来源由服务端固定为 `NONE`。

### 5.2 confirm：人工确认远端已成功

```json
{
  "expectedVersion": 3,
  "reasonCode": "REMOTE_TICKET_VERIFIED",
  "decisionNote": "已在受信任售后后台核对",
  "result": {
    "ticketId": "AS-20260713-001",
    "status": "PENDING",
    "createdAt": "2026-07-13T08:00:00+08:00"
  }
}
```

状态变化：

```text
NEEDS_MANUAL_REVIEW -> CONFIRMED
```

`createdAt` 必须是合法 ISO-8601 Instant，服务端会统一规范成 UTC，例如上面的时间最终保存为
`2026-07-13T00:00:00Z`。当前入口表示管理员已经在受信任后台核对，因此证据来源固定为
`ADMIN_PROVIDED`。客户端不能把它改成 20.5 才会使用的 `AFTER_SALES_IDEMPOTENCY_QUERY`。

### 5.3 close：人工关闭

```json
{
  "expectedVersion": 3,
  "reasonCode": "NO_TICKET_REQUIRED",
  "decisionNote": "确认无需创建或关联远端工单"
}
```

状态变化：

```text
NEEDS_MANUAL_REVIEW -> MANUALLY_CLOSED
```

关闭不是远端成功，所以不会伪造 `confirmedTicket`。这是不可自动恢复的终态，迟到 Worker 不能重新激活
记录；证据来源固定为 `NONE`。

## 6. expectedVersion 为什么必须提交

管理员读取详情时会得到当前 `version`，执行决定时必须把它作为 `expectedVersion` 原样带回：

```text
管理员 A 读到 version=3
管理员 B 读到 version=3
管理员 A confirm 成功，记录变成 version=4
管理员 B 仍用 expectedVersion=3 close
  -> 409 PENDING_HANDOFF_VERSION_CONFLICT
  -> B 必须刷新后重新判断，不能覆盖 A 的真实结果
```

Store 还会检查当前状态必须仍为 `NEEDS_MANUAL_REVIEW`。版本解决“页面是否过期”，状态解决“该动作现在
是否允许”，两者缺一不可。

## 7. 返回 DTO 的数据最小化

生产代码禁止 Controller 直接返回 `PendingHandoffRecord`。所有输出都经过
`PendingHandoffAdminResponseMapper` 的字段白名单。

| 响应 | 允许返回 | 刻意不返回 |
| --- | --- | --- |
| 列表项 | outboxId、状态、转人工原因、失败码、尝试次数、调度时间、版本、时间戳 | 摘要、工单详情、任何用户身份、完整请求 |
| 详情 | 列表字段、脱敏摘要预览、产品线/问题类型/优先级、最小工单结果 | tenantId、userId、conversationId、customerId、idempotencyKey、请求标题和描述 |
| 审计事件 | action、脱敏 actor、实际权限、处置原因、脱敏备注、证据、前后状态/版本、时间 | tenantId、outboxId、完整业务请求 |

详情中的 `handoffReasonCode` 表示“为什么触发转人工”；请求和审计中的 `reasonCode` 表示“为什么执行本次
人工决定”。两个名称故意不同，避免前端把两种业务含义混为一谈。

手机号、邮箱、凭据、身份证和长数字会替换为 `[REDACTED]`，换行会压成单行。列表完全不返回自由文本，
减少批量读取敏感信息的风险。内部 `nextAttemptAt=0` 和 `Long.MAX_VALUE` 只是无调度哨兵，对外统一为
`null`，不会让前端猜测内部实现。

一次决定的响应同时包含更新后详情和本次审计：

```json
{
  "record": {
    "outboxId": "4df0...",
    "status": "RETRY_SCHEDULED",
    "handoffReasonCode": "COMPLAINT",
    "failureCode": "MANUAL_RETRY_REQUESTED",
    "summaryPreview": "用户联系方式 [REDACTED]",
    "productLineCode": "general",
    "issueTypeCode": "complaint",
    "priorityCode": "HIGH",
    "confirmedTicket": null,
    "attemptCount": 2,
    "scheduledAt": 1783950000000,
    "version": 4,
    "createdAt": 1783940000000,
    "updatedAt": 1783950000000
  },
  "auditEvent": {
    "auditId": "84a1...",
    "action": "RETRY",
    "actorId": "USER-001",
    "authorityUsed": "ADMIN",
    "reasonCode": "MANUAL_RETRY_APPROVED",
    "decisionNote": "已检查平台状态，可以重新尝试",
    "evidenceSource": "NONE",
    "fromStatus": "NEEDS_MANUAL_REVIEW",
    "toStatus": "RETRY_SCHEDULED",
    "fromVersion": 3,
    "toVersion": 4,
    "reviewFailureCode": "SocketTimeoutException",
    "attemptCountAtDecision": 2,
    "scheduledAt": 1783950000000,
    "occurredAt": 1783950000000
  }
}
```

## 8. 游标为什么绑定用途和筛选条件

Store 使用结构化 keyset 游标：

- 列表：`updatedAt + outboxId`；
- 审计：`occurredAt + auditId`。

HTTP 层把它编码成带版本的 Base64URL JSON，并额外保存用途和筛选绑定：

```text
列表游标：version + kind=review + status + 排序坐标
审计游标：version + kind=audit + resourceOutboxId + 排序坐标
```

因此系统会拒绝：

- 把 `NEEDS_MANUAL_REVIEW` 列表游标复制到 `CONFIRMED` 列表；
- 把列表游标当作审计游标；
- 把一条 Outbox 的审计游标复制到另一条；
- 损坏 Base64、非法 JSON、未知版本、负时间或超长载荷。

Base64URL 是编码，不是加密或签名。游标内只保存非敏感排序坐标，授权仍然由 JWT 租户条件保证。客户端应
把游标当作不透明文本；收到 `INVALID_PENDING_HANDOFF_CURSOR` 后丢弃它并从第一页重新查询。

## 9. 稳定错误契约

| HTTP | errorCode | 含义 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST_BODY` | JSON 无法解析 |
| 400 | `VALIDATION_ERROR` | 请求 DTO 的必填、长度或格式校验失败 |
| 400 | `INVALID_REQUEST_PARAMETER` | `limit` 等查询参数无法转换类型 |
| 400 | `INVALID_PENDING_HANDOFF_CURSOR` | 游标损坏、错版本、错用途或跨筛选复用 |
| 400 | `INVALID_PENDING_HANDOFF_REQUEST` | status、limit、路径 ID、时间或领域参数不符合边界 |
| 401 | `AUTHENTICATION_REQUIRED` / `AUTHENTICATION_FAILED` | 缺少或无法验证 JWT |
| 403 | `ACCESS_DENIED` | 已认证，但不是 ADMIN |
| 404 | `PENDING_HANDOFF_NOT_FOUND` | 当前 JWT 租户内不可见；跨租户也使用同一响应 |
| 409 | `PENDING_HANDOFF_VERSION_CONFLICT` | 页面版本过期或并发 CAS 未命中 |
| 409 | `PENDING_HANDOFF_STATE_CONFLICT` | 当前状态不允许本次人工动作 |
| 409 | `PENDING_HANDOFF_RESULT_CONFLICT` | 新确认结果与已有权威结果冲突 |
| 500 | `INTERNAL_ERROR` | 未预期技术失败，响应不回显 SQL、凭据或异常原文 |

## 10. 核心类与阅读顺序

建议初学者按下面顺序阅读：

1. `PendingHandoffAdminController`：先看六个 HTTP 入口接收哪些字段；
2. `HttpSecurityConfiguration`：看 URL 为什么只允许 ADMIN；
3. `PendingHandoffAdminService`：重点看 Principal、租户、证据来源和状态命令怎样组合；
4. `PendingHandoffAdminDecisionRequest` 与 `PendingHandoffAdminConfirmRequest`：理解哪些字段允许客户端提交；
5. `PendingHandoffAdminCursorCodec`：理解 HTTP 不透明游标与 Store 结构化游标的边界；
6. `PendingHandoffAdminResponseMapper`：理解为什么不能直接返回领域 Record；
7. `PendingHandoffManualDecisionStore`：回到 20.3 看状态和审计为什么必须原子写入；
8. `InMemoryPendingHandoffStore.applyManualDecision()`：用 Map CAS 理解一次决定；
9. `JdbcPendingHandoffStore.applyManualDecision()`：再对应到真实事务、行锁和审计 INSERT；
10. `PendingHandoffAdminIntegrationTest`：最后沿真实 JWT + HTTP + Memory Store 看完整闭环。

## 11. 测试与验收结果

20.4 新增或扩展的自动化证据包括：

- `PendingHandoffAdminCursorCodecTest`：版本、用途、status/outbox 绑定、损坏和长度边界；
- `PendingHandoffAdminResponseMapperTest`：字段集合、摘要/备注脱敏、身份与完整请求不泄露；
- `PendingHandoffAdminServiceTest`：Principal、租户、游标、三种命令与非法输入零写入；
- `PendingHandoffAdminControllerMvcTest`：URL、JSON、Bean Validation、RBAC 和安全异常；
- `PendingHandoffAdminIntegrationTest`：真实 JWT、SecurityFilterChain、Service、Memory Store、三种决定与审计回读；
- `HttpSecurityIntegrationTest`：无 JWT、CUSTOMER、MCP_CLIENT、ADMIN 的完整角色矩阵；
- `GlobalExceptionHandlerTest`：400、404、409 和安全 500 错误契约。

端到端测试会故意在请求体中放入伪造 tenant、actor 和 evidence，确认最终审计 actor 仍是 JWT 的
`USER-001`；同时用包含手机号、邮箱、凭据、客户 ID 和幂等键的真实 Store 记录验证响应没有泄露这些字段。

最终本地验收：

```text
默认测试：497 项，0 失败、0 错误、0 跳过
跳过测试 JAR 打包：通过
```

20.4 没有修改 JDBC SQL、表结构或事务实现，因此没有重复把 SQL 替身测试描述成真实数据库验收；20.3
已经完成的真实 MySQL 原子审计证据仍然有效。完整真实 MySQL、Agent 镜像和 Compose 总验收按计划在
20.6 统一执行。

## 12. 部署、回滚和当前边界

1. 20.4 不新增数据库表，但 JDBC 模式必须已经具备 20.1 的租户复合结构和 20.3 的审计表。
2. 发布后只有 ADMIN JWT 能访问新 URL；生产必须继续关闭 Demo issuer，并使用真实 IdP。
3. 管理客户端应先读详情版本，再提交决定；409 后必须刷新，不应自动用旧决定重试。
4. retry 只是重新排队。只有已经确认售后平台支持可靠幂等键时，才能启用 Recovery Worker。
5. confirm 当前依赖管理员提供的受信任证据。它不是服务端远端查询，审计会诚实标记为 `ADMIN_PROVIDED`。
6. 若必须回滚 20.4，应先停止管理写流量再回滚应用；20.3 审计表和已写历史可以保留。
7. 当前没有管理端 UI、批量处置、审批双人复核或自动售后查询，这些都不能从已有 API 名称推断为已完成。

下一步 20.5 将先审计售后平台是否真的提供按租户与 `Idempotency-Key` 查询结果的接口。如果支持，再实现
可替换查询 Adapter；如果不支持，就提供明确返回 unavailable 的 Port，不能把“查不到”错误解释为“远端
一定没有创建”。
