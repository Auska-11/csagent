# 客服Agent-42：会话所有权边界改造说明

> 后续更新：本文记录 Phase 8 第一批的历史实现。第七批已用 Spring Security Bearer JWT、
> `JwtPrincipalConverter` 和统一 RBAC 替换 `DemoAuthenticationService`、body token 与 `X-User-Id`。
> 当前实现请以 `客服Agent-48-JWT统一认证与角色权限说明.md` 为准，下面内容用于理解迁移过程。

## 1. 本批次结论

Phase 8 第一批已经完成“会话所有权边界”改造。

会话状态、会话锁、Trace/幂等作用域不再只依赖 `conversationId`，而是统一基于：

```text
(tenantId, userId, conversationId)
```

HTTP 层也不再把客户端随意填写的 `userId` 当成可信身份：

- Chat 必须通过 token 恢复 `AuthenticatedPrincipal`，再由 Principal 决定 owner。
- 未认证 Chat 在进入 Agent 和 ConversationStore 前直接返回 401。
- 会话 Memory 和用户长期 Memory 必须提供 Bearer token。
- 读取不到自己的复合键时统一返回“未找到”，不会说明同名会话是否属于其他用户。

这解决的是 Phase 8 中最直接的一类越权风险，但不等于完整生产认证已经完成。当前仍然使用
`DemoAuthenticationService`，真实 JWT、网关租户、RBAC 和持久化 SessionStore 在后续批次继续实现。

## 2. 旧实现有什么问题

旧 `ConversationStore` 的核心结构是：

```text
Map<conversationId, ConversationState>
Map<conversationId, ReentrantLock>
```

这相当于把 `conversationId` 同时当作记录主键和访问凭证。它带来三个问题。

### 2.1 猜中 ID 就可能读取或删除

旧接口：

```text
GET    /api/chat/conversations/{conversationId}/memory
DELETE /api/chat/conversations/{conversationId}/memory
```

没有要求 token，也没有提供 owner 信息。只要知道或猜到 `conversationId`，就可以调用
`get(conversationId)` 或 `clear(conversationId)`。

这种问题通常称为 IDOR（不安全的直接对象引用）：系统只检查“资源 ID 对不对”，没有检查
“当前调用者是否拥有这个资源”。

### 2.2 不同用户的同名会话发生碰撞

如果 USER-001 和 USER-002 都使用 `shared-conv`，旧 Map 只能保存一个状态。后创建者可能被拒绝，
也可能在未来持久化改造中覆盖前一个状态。无论哪种结果，都说明存储主键没有表达真实业务身份。

### 2.3 无 token 的 userId 声明仍可冒充 owner

旧聊天入口会调用认证服务，但 token 缺失时得到的是 `unverifiedClaim(userId)`。如果 Controller
随后仍用这个 claim 建立会话键，攻击者可以不带 token，直接声称自己是 USER-001 并尝试续接
USER-001 的会话。

因此只改 Map key 还不够，外部身份进入 ChatRequest 之前也必须区分“客户端声明”和“服务端验证”。

## 3. 新身份模型

新增不可变值对象：

```java
public record ConversationKey(
        String tenantId,
        String userId,
        String conversationId
) {
}
```

可以把它理解为数据库中的复合主键：三个字段共同相同，才是同一条会话。

| tenantId | userId | conversationId | 是否同一会话 |
| --- | --- | --- | --- |
| `default` | `USER-001` | `shared` | 基准会话 |
| `default` | `USER-002` | `shared` | 否，用户不同 |
| `tenant-b` | `USER-001` | `shared` | 否，租户不同 |
| `default` | `USER-001` | `other` | 否，会话 ID 不同 |

`record` 天然不可变，并自动提供 `equals` 和 `hashCode`，很适合充当 `ConcurrentHashMap` 的 key。
构造时还会拒绝空 tenant、user 和 conversation，并统一去掉首尾空格。

## 4. 组件调整

| 组件 | 本批次职责变化 |
| --- | --- |
| `ConversationKey` | 定义租户、用户、会话三元身份，并生成无歧义字符串作用域 |
| `ChatRequest` | 新增 `tenantId`；旧构造函数继续使用默认租户，减少迁移范围 |
| `ConversationStore` | 状态 Map 和锁 Map 都改用 `ConversationKey` |
| `ChatApiController` | 只用可信 Principal 构造 owner key；Memory 接口要求 Bearer token |
| `DemoAuthenticationService` | 无效 token 和 claim/token 不一致统一抛认证异常 |
| `GlobalExceptionHandler` | 认证失败返回 401，资源不存在返回 404 |
| `CapabilityActionHandler` | tenant 和复合 owner 作用域继续传入 `ExecutionContext` |
| `HandoffActionHandler` | 人工工单的 tenant、traceId 和幂等键不再写死默认租户 |
| `index.html` | 四个 Memory 请求统一使用认证 Header helper |

## 5. 三条访问链路如何工作

### 5.1 已认证聊天与续接

```text
userId claim + token
        |
        v
DemoAuthenticationService
        | token 对应的真实 userId
        v
AuthenticatedPrincipal
        |
        v
ChatRequest(default, principal.userId, conversationId)
        |
        v
ConversationKey -> executeSerially -> Workflow
```

即使请求体把 `userId` 改成别人，只要 token 不匹配，认证会在进入 Agent 前失败。

### 5.2 未认证聊天

HTTP Chat 不再建立匿名会话。无 token 时，Controller 在调用 Agent 前返回：

```text
HTTP 401
errorCode = AUTHENTICATION_REQUIRED
```

原因是匿名调用者没有稳定、可信的 owner，无法安全恢复多轮状态。如果为每个匿名请求创建随机会话，
又会持续增加内存状态和锁；如果所有匿名请求共用一个 ID，则会互相看到或污染状态。因此本批次选择
明确要求 Chat 认证。

`CustomerServiceAgent.chat(...)` 的纯 Java Legacy 便捷入口仍然保留，供旧测试和内部兼容调用使用，
但它不是对外 HTTP 认证边界。

### 5.3 Memory 读取与清除

前端发送：

```http
X-User-Id: USER-001
Authorization: Bearer token-zhangsan
```

`X-User-Id` 只是 claim，Bearer token 才是身份依据。Controller 使用认证结果构造：

```text
ConversationKey(default, principal.userId, path.conversationId)
```

如果 USER-002 请求同一个 `conversationId`，系统只会查询 USER-002 自己的复合键。即使 USER-001
存在同名会话，也不会被读取或删除。

用户长期 Memory 同样要求 token，并校验路径中的 `userId` 与 token 所属用户一致。

## 6. 状态、锁和幂等键为什么要一起改

只把状态 Map 改成复合键还不完整。

### 6.1 锁必须使用同一个复合键

当前单进程串行边界是：

```text
Map<ConversationKey, ReentrantLock>
```

同一个 owner 的同一个会话仍然串行执行；不同用户即使使用相同 `conversationId`，也不会无故争用
同一把锁。

`clear` 现在也会先获取同一把锁。这样正在运行的一轮会先保存完成，清除操作随后删除最终状态，
不会出现“刚清空，旧请求又把状态写回来”的竞态。

### 6.2 幂等键必须包含 owner 作用域

Capability 和人工工单的幂等记录不能只按 `conversationId` 建立，否则不同租户的同名会话可能
误复用彼此的执行结果。

`ConversationKey.toScopedId()` 使用长度前缀生成无歧义字符串，例如：

```text
8:tenant-a8:USER-00111:shared-conv
```

该作用域进入 traceId 和 idempotencyKey，下游仍然分别收到 tenantId、Principal 和 conversationId。

## 7. HTTP 状态约定

| 场景 | HTTP 状态 | errorCode |
| --- | --- | --- |
| Memory 未提供 token | 401 | `AUTHENTICATION_REQUIRED` |
| token 无效或 claim/token 不一致 | 401 | `AUTHENTICATION_FAILED` |
| 当前 owner 下不存在会话 | 404 | `NOT_FOUND` |
| 普通参数错误 | 400 | `BAD_REQUEST` 或 `VALIDATION_ERROR` |

越权读取和正常不存在使用同一个 404 响应，目的是避免泄露“这个 conversationId 确实存在，只是
属于另一个人”。

## 8. 测试矩阵

本批次新增或加强了以下负向测试：

| 测试场景 | 预期结果 |
| --- | --- |
| 两个用户使用相同 conversationId | 保存为两个独立状态 |
| 同一用户在两个 tenant 使用相同 conversationId | 保存为两个独立状态 |
| 清除用户一的同名会话 | 用户二状态保持不变 |
| 把 USER-001 状态保存到 USER-002 key | Store 拒绝 |
| 同一复合 key 并发两轮 | 临界区最大并发数为 1 |
| 不同 owner 的同名会话并发 | 可以并行，不共享锁 |
| Workflow 运行时同时 clear | clear 等待本轮完成，然后删除最终状态 |
| Memory 无 token | 进入 Store 前返回 401 |
| claim 与 token 不一致 | 返回统一 401，不泄露 token 属于谁 |
| USER-002 读取 USER-001 同名会话 | 只查 USER-002 key，并返回 404 |
| USER-002 清除 USER-001 同名会话 | cleared=false，owner 状态保留 |
| USER-002 token 读取 USER-001 长期记忆 | 返回 401 |
| tokenless Chat 声称 USER-001 | 进入 Agent 前返回 401 |
| Capability/Handoff 自定义 tenant | ExecutionContext 收到原 tenant，不回退 default |

最终自动化验证：

```text
mvn test
Tests run: 272, Failures: 0, Errors: 0, Skipped: 0

mvn -q -DskipTests package
BUILD SUCCESS
```

## 9. 前端变化

前端新增 `currentAuthHeaders()`，统一生成：

```javascript
{
    "X-User-Id": currentUserId(),
    "Authorization": "Bearer " + token
}
```

以下四个请求都复用该 helper：

- 读取会话 Memory。
- 清除会话 Memory。
- 读取用户长期 Memory。
- 清除用户长期 Memory。

token 没有放到 query string，避免它进入浏览器历史、代理访问日志或普通 URL 复制内容。
聊天 POST 暂时继续使用请求体里的 demo token，后续统一认证 Filter 时再迁移到 Header。

## 10. 当前限制

1. `ConversationStore` 仍是单 JVM 内存实现，服务重启后会话丢失。
2. `ReentrantLock` 只能保护一个进程，多个实例之间还需要数据库版本号、CAS 或分布式锁。
3. `DemoAuthenticationService` 和 `/api/chat/users` 中的演示 token 只适合本地学习，不能作为生产登录方案。
4. 当前 HTTP tenant 固定为 `default`；真实 tenant 必须由网关、JWT 或服务端 Session 注入。
5. `AuthenticatedPrincipal` 目前只保存 userId 和 roles，后续可评估把可信 tenant 也纳入 Principal。
6. Chat 已要求 demo token，但真实登录态、token 轮换、撤销、过期和限流尚未实现。
7. Monitor、MCP 管理接口和工具管理接口尚未统一 RBAC。
8. ConversationState 还没有持久化 schemaVersion 和迁移器。
9. Memory 的 TTL、容量上限、脱敏和摘要上限仍待实现。
10. Trace 敏感字段审计属于 Phase 8 后续批次。

## 11. 下一批建议

下一批优先实现 SessionStore SPI 和持久化适配器：

```text
SessionStore
  |- InMemorySessionStore（测试和 Demo）
  `- JdbcSessionStore（生产 Profile）
```

建议同时引入：

1. `SessionRecord`：key、stateJson、schemaVersion、version、updatedAt。
2. 乐观锁：`UPDATE ... WHERE version = ?`，防止多实例丢失更新。
3. `ConversationStateCodec`：集中负责 JSON 序列化，避免 Repository 理解 Workflow 细节。
4. `WorkflowStateMigrator`：按 schemaVersion 升级旧状态。
5. 重启恢复测试：保存等待用户状态，重建 Store 后继续下一轮。
6. 多实例冲突测试：两个执行者同时更新同一版本，只允许一个提交成功。

这一批完成后，Phase 8 才会从“单进程安全隔离”进入“可重启、可多实例运行”的状态。
