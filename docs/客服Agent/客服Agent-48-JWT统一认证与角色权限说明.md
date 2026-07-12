# 客服Agent-48：JWT 统一认证与角色权限说明

## 1. 本批次结论

Phase 8 第七批完成了 HTTP 身份入口的统一：Controller 不再自己读取演示 token，也不再把请求体、
`X-User-Id` 或 MCP `_meta` 中的 userId 当成可信身份。

现在的规则只有一条：

> 外部请求的 tenantId、userId 和 roles，只能来自 Spring Security 已验证的 Bearer JWT。

本批已经覆盖 Chat、Memory、Monitor、Eval、Tool、MCP 和 LLM Demo 接口，并建立 CUSTOMER、ADMIN、
MCP_CLIENT 三类实际使用角色。第七批通过全量 332 个默认测试，但 Phase 8 仍未整体结束。

## 2. 为什么不能继续在 Controller 里手工验 token

旧方案由各个 Controller 或 Demo 服务分别处理 token，容易出现以下问题：

1. 有的接口读取 Header，有的读取 JSON body，认证规则不一致。
2. 新增接口时容易忘记鉴权，形成未保护入口。
3. `userId` 和 token 分开传递时，开发者可能误用客户端填写的 userId。
4. Spring Security 之前没有统一拦截请求，401、403 和业务 400 容易混在一起。
5. MCP service token 放在 JSON-RPC body 中时，代理层无法在进入 Controller 前认证。

统一以后，认证发生在 Controller 之前。无效 JWT 根本不会进入业务方法，Controller 只接收已经转换好的
`AuthenticatedPrincipal`。

## 3. 一次 HTTP 请求怎样建立身份

```text
HTTP Request
  -> Authorization: Bearer <JWT>
  -> Spring Security BearerTokenAuthenticationFilter
  -> NimbusJwtDecoder
       -> 固定 HS256
       -> 校验签名
       -> 校验 exp / nbf（如果存在）和允许的时钟偏差
       -> 校验 iss
       -> 校验 aud
       -> 要求 sub 和 tenant_id 非空
  -> JwtPrincipalConverter
       -> sub       -> userId
       -> tenant_id -> tenantId
       -> roles     -> 规范化角色与 ROLE_* authorities
  -> AuthenticatedPrincipal
  -> SecurityFilterChain 角色判断
  -> Controller
  -> Agent / Monitor / MCP Adapter
```

关键类：

| 类 | 职责 |
| --- | --- |
| `SecurityProperties` | 保存 issuer、audience、密钥、TTL、时钟偏差和 Demo 开关 |
| `HttpSecurityConfiguration` | 创建 JWT Encoder/Decoder，并声明全部 URL 角色规则 |
| `JwtPrincipalConverter` | 把已验证 JWT 转换成项目内部 Principal |
| `AuthenticatedPrincipal` | 向 Agent、Capability 和 Controller 提供可信 tenant/user/roles |
| `SecurityErrorWriter` | 让过滤器产生的 401/403 也返回统一 JSON 错误 |
| `DemoJwtTokenService` | 仅为本地工作台签发预置身份的短期 JWT |
| `DemoAuthController` | 暴露可关闭的本地 Demo JWT 入口 |

## 4. JWT 中使用哪些字段

| 字段 | 用途 | 是否必须 |
| --- | --- | --- |
| `iss` | 证明由允许的签发者签发 | 是 |
| `aud` | 证明令牌确实发给当前 API | 是 |
| `sub` | 调用主体，转换为 `principal.userId` | 是 |
| `tenant_id` | 多租户数据边界 | 是 |
| `roles` | URL 和业务角色判断 | 可以为空，但空角色无法访问受保护接口 |
| `iat` | 签发时间 | Demo token 会提供 |
| `exp` | 过期时间 | 是 |
| `nbf` | 在某时刻前不可使用 | 外部签发者可选；存在时会校验 |

`tenant_id` 缺失时不再回退到默认租户。默认租户只用于本地签发器生成令牌，不能用于补全一个不完整的
外部 JWT，否则多租户系统可能把错误令牌静默放进 `default` 数据空间。

## 5. 角色与接口矩阵

| 接口 | 允许角色 |
| --- | --- |
| `/`、静态首页、`/api/health`、`/actuator/health/**` | 公开 |
| `POST /api/auth/demo/tokens` | 公开地址；Demo 开关关闭时返回 404 |
| `/api/chat/**` | CUSTOMER 或 ADMIN |
| `POST /api/monitor/feedback` | CUSTOMER 或 ADMIN |
| `/api/monitor/**` 其余接口 | ADMIN |
| `/api/eval/**` | ADMIN |
| `/api/tools/**` | ADMIN |
| `/api/demo/**` | ADMIN |
| `/api/mcp/**`、`/mcp` | MCP_CLIENT 或 ADMIN |
| 未明确声明的其他地址 | 默认拒绝 |

代码中的角色常量不带 `ROLE_` 前缀，例如 `ADMIN`。`JwtPrincipalConverter` 会同时生成 Spring Security
需要的 `ROLE_ADMIN` authority，避免在业务代码中混用两套写法。

`MCP_TRUSTED_CALLER` 当前只是为未来签名委托预留的名称，本批没有签发或授权该角色。

## 6. Chat 与 Memory 的身份边界

聊天请求现在应这样发送：

```http
POST /api/chat/send
Authorization: Bearer <CUSTOMER_JWT>
Content-Type: application/json

{
  "message": "帮我查订单 ORD-001",
  "conversationId": "demo-001"
}
```

Controller 使用：

```text
principal.tenantId + principal.userId + request.conversationId
```

构造 `ConversationKey`。请求体里的旧 `userId` 字段暂时保留给旧客户端做一致性检查，但它不能创建
身份；与 JWT 不一致时请求会失败。旧 `token` 字段也继续保留 DTO 兼容，但服务端完全不读取它。

用户长期 Memory 的 URL 中仍包含 userId，原因是资源路径需要明确目标；真正授权仍以 Principal 为准。
访问其他用户路径时统一返回 404，避免泄露该用户或记忆是否存在。

## 7. 用户反馈为什么也要校验会话

`POST /api/monitor/feedback` 是客户行为，因此 CUSTOMER 可以调用；Monitor 汇总、审核、导出和合并仍然
只允许 ADMIN。

反馈入口执行三层检查：

1. 提交者 userId 只取 JWT Principal。
2. 使用 `(tenantId, userId, conversationId)` 从服务端 ConversationStore 读取会话。
3. `assistantReply` 必须等于该会话最后一条服务端助手回复。

请求体中的 userId 和 assistantReply 是旧页面兼容字段，只做一致性检查。这样可以阻止攻击者替其他用户
提交反馈，或伪造一段助手回复污染后续 Eval 候选样本。

## 8. MCP 服务身份不等于用户身份

这是本批最重要的安全边界。

```text
MCP_CLIENT JWT
  只能证明：mcp-client-a 这个服务正在调用
  不能证明：它代表 USER-001
```

因此以下做法不可信：

```json
{
  "_meta": {
    "userId": "USER-001",
    "tenantId": "default"
  }
}
```

任何客户端都能修改普通 JSON，`_meta` 不能把服务身份升级为客户身份。当前规则是：

| 调用 | 结果 |
| --- | --- |
| MCP_CLIENT 调用公开 Capability | 可以继续执行 |
| MCP_CLIENT 调用需要身份/认证的 Capability | `MCP_DELEGATION_REQUIRED` |
| `_meta` 试图覆盖已认证 Principal | `MCP_IDENTITY_METADATA_REJECTED` |
| 未认证调用 MCP HTTP 入口 | 401 |
| CUSTOMER 调用 MCP HTTP 入口 | 403 |

`HttpMcpClientTransport` 现在会在 initialize、tools/list 和 tools/call 的每个 HTTP 请求中发送：

```http
Authorization: Bearer <MCP_CLIENT_JWT>
```

service token、userId 和 tenantId 不再放进 JSON-RPC body。

未来如果要让远程 MCP 调用私有 Capability，必须增加签名用户委托或 token exchange。委托至少需要绑定：

```text
actor/clientId
subject/userId
tenantId
audience
expiry
capability scope
```

在这套协议完成前，失败关闭比“先相信 `_meta.userId`”更安全。

## 9. 本地 Demo JWT 怎样使用

本地页面先调用：

```http
POST /api/auth/demo/tokens
```

接口只返回预置身份，调用者不能自行指定角色、租户或有效期。响应带：

```http
Cache-Control: no-store
```

页面把 token 保存在 JavaScript 内存状态中，不写入 localStorage，也不放在 URL 或请求体里。

生产环境必须配置：

```text
SMARTCS_SECURITY_DEMO_ISSUER_ENABLED=false
SMARTCS_SECURITY_JWT_SECRET=<至少 32 字节随机密钥>
```

关闭后 Demo 地址返回 404，而不是暴露“这里有一个被关闭的本地登录器”。

## 10. 配置项

```text
SMARTCS_SECURITY_DEMO_ISSUER_ENABLED=true
SMARTCS_SECURITY_ISSUER=smart-cs-agent
SMARTCS_SECURITY_AUDIENCE=smart-cs-api
SMARTCS_SECURITY_DEFAULT_TENANT_ID=default
SMARTCS_SECURITY_JWT_SECRET=replace-with-at-least-32-random-bytes
SMARTCS_SECURITY_TOKEN_TTL_SECONDS=28800
SMARTCS_SECURITY_CLOCK_SKEW_SECONDS=30
SMARTCS_MCP_REMOTE_TOKEN=<远程服务签发的 MCP_CLIENT JWT>
```

HS256 适合当前单体学习项目，但多个服务共享同一个对称密钥时，每个验证者理论上也能签发 ADMIN token。
生产跨服务架构应优先使用独立 IdP 与 RS256/ES256，让业务服务只持有公钥而不持有签名私钥。

## 11. 401、403、404 怎样区分

| 状态 | 含义 | 示例 errorCode |
| --- | --- | --- |
| 401 | 没有凭据，或 JWT 验证失败 | `AUTHENTICATION_REQUIRED` / `AUTHENTICATION_FAILED` |
| 403 | 身份有效，但角色或资源操作不允许 | `ACCESS_DENIED` |
| 404 | 资源不存在，或故意隐藏其他用户资源 | `NOT_FOUND` |

Spring Security 的异常发生在 Controller 之前，不会进入 `GlobalExceptionHandler`。因此本批新增
`SecurityErrorWriter`，保证过滤器层和 Controller 层都返回项目统一的 `ApiErrorResponse`。

## 12. 自动化测试证据

| 测试 | 覆盖内容 |
| --- | --- |
| `SecurityPropertiesTest` | 拒绝短密钥、空 issuer/audience/tenant、非法 TTL 和时钟偏差 |
| `JwtPrincipalConverterTest` | subject、tenant、角色和 `ROLE_` authority 映射 |
| `HttpSecurityIntegrationTest` | 真实签名验证、401/403、角色矩阵、反馈防伪和 MCP 身份边界 |
| `DemoIssuerDisabledIntegrationTest` | 生产关闭 Demo 签发器后返回 404 |
| `HttpMcpClientTransportTest` | 每次 MCP HTTP 请求都有 Bearer JWT，JSON body 不再携带身份凭据 |
| `HealthEndpointIntegrationTest` | liveness 与旧兼容健康接口保持公开 |

负向场景覆盖错误 issuer、错误 audience、过期 JWT、缺少 expiration、缺少 subject、缺少 tenant、body token 绕过、
CUSTOMER 访问管理接口、MCP_CLIENT 访问客户接口、反馈 userId 冒充和回复投毒。

## 13. 仍然存在的不足

1. 当前没有真实登录、OIDC、刷新令牌、撤销列表或账户停用联动。
2. HS256 密钥仍由单体配置持有，尚未接独立 IdP、非对称密钥和 `kid` 轮换。
3. Demo token 默认有效期面向本地工作台，不应直接作为生产会话策略。
4. MCP 用户委托和 capability scope 尚未实现，因此远程身份型 Capability 当前主动拒绝。
5. 还没有按租户/用户/令牌维度增加速率限制和安全审计事件。
6. Trace、Prompt、最近消息和工具参数仍需统一结构化脱敏测试。

这些限制说明本批完成的是可靠的认证与 RBAC 基线，不是完整身份平台，也不是 Phase 8 的最终完成点。
