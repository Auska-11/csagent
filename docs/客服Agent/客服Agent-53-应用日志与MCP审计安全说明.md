# 客服 Agent 应用日志与 MCP 审计安全说明

## 1. 本批解决什么问题

Phase 8 第八批已经让公开 Trace 自动脱敏，但普通应用日志不是 Trace 的一部分。审计发现还有两条独立
泄露路径：

1. LangChain4j 默认记录完整请求和响应时，Prompt、用户原话、订单信息和模型回复可能直接进入日志。
2. MCP Server 只记录成功进入 Gateway 的调用，认证、委托和身份冲突三个提前返回分支没有审计；
   外部 requestId 和 capabilityId 还可能携带换行、控制字符或敏感文本。

本批完成两个收口：

- 模型完整请求和响应日志默认关闭，并用测试锁定生产配置。
- MCP 所有正常返回路径统一经过最小化审计出口。

本批完成后默认测试套件共 `370` 项，0 失败、0 错误、0 跳过。

## 2. 为什么 Trace 脱敏不能自动保护普通日志

公开 Trace 的调用链是：

```text
TurnContext.addTrace
  -> TraceEvent
  -> TraceDataSanitizer
  -> ChatResponse / Monitor
```

LangChain4j 和 SLF4J 日志走的是另一条链：

```text
ChatModel / McpServerAdapter
  -> Logger
  -> Console / File / Log Collector
```

如果模型组件自己打印 Prompt，它根本不会经过 `TraceEvent`。因此不能因为“Trace 已脱敏”就认为系统
所有日志都安全，必须分别治理每一个输出边界。

## 3. 模型原文日志默认关闭

`application.yml` 现在明确配置：

```yaml
langchain4j:
  open-ai:
    chat-model:
      log-requests: false
      log-responses: false
```

这两个开关关闭后：

- 不把完整 System Prompt 写入普通日志。
- 不把最近会话消息写入普通日志。
- 不把工具结果中的订单、退货或用户信息写入普通日志。
- 不把完整模型回复写入普通日志。

`ApplicationLogSafetyTest` 会直接读取并解析生产 `application.yml`。如果以后升级 LangChain4j、复制配置
或调试后忘记恢复，测试会在 CI 中失败。

### 为什么不提供生产环境的“临时开启”示例

模型请求通常混合系统指令、用户原话、知识库片段和工具结果。即使只为了排障，完整记录也可能违反
最小化原则。优先使用：

- 已脱敏 Trace 中的事件、错误码和耗时。
- Capability outcome 和 providerId。
- MCP requestRef。
- 受控测试环境中的合成数据。

若未来确实需要受限原文审计，应使用独立安全存储、权限审批、加密、保留期和访问审计，而不是重新打开
普通 INFO 日志。

## 4. MCP 原实现的审计盲区

旧 `McpServerAdapter.invoke` 有四个返回位置：

```text
未认证 --------------------> 直接返回，无日志
MCP_CLIENT 缺少用户委托 ---> 直接返回，无日志
_meta 身份冲突 ------------> 直接返回，无日志
Gateway 调用完成 ----------> 记录日志
```

这会产生一个反常结果：成功调用有记录，越权和身份伪造尝试反而没有记录。

新实现先生成统一 `CapabilityResult`，最后只从一个出口返回：

```text
认证检查
  -> 委托检查
  -> 身份冲突检查
  -> Gateway
  -> auditResult
  -> return
```

因此以下结果都会审计：

- `MCP_AUTHENTICATION_REQUIRED`。
- `MCP_DELEGATION_REQUIRED`。
- `MCP_IDENTITY_METADATA_REJECTED`。
- Gateway 返回的业务拒绝。
- Gateway 返回的技术失败。
- 正常成功和幂等回放。

## 5. 审计日志保存哪些字段

一条日志只保留：

| 字段 | 作用 | 安全处理 |
| --- | --- | --- |
| serverId | 区分 MCP 服务实例 | 脱敏、单行化、限长 |
| requestRef | 关联同一外部请求 | requestId 的 SHA-256 前 16 位 |
| capability | 定位调用的能力 | 脱敏、字符白名单、限长 |
| callerType | 区分匿名、服务、管理员 | 只记录粗粒度固定枚举 |
| provider | 定位本地或远程实现 | 脱敏、字符白名单、限长 |
| outcome | 成功、业务拒绝或技术失败 | 固定枚举 |
| errorCode | 稳定机器错误码 | 脱敏、字符白名单、限长 |
| replayed | 是否来自幂等回放 | 布尔值 |
| durationMs | MCP Adapter 调用耗时 | 非负整数 |

明确不记录：

- arguments。
- Bearer token 或旧 authToken。
- userId 和 tenantId。
- conversationId。
- idempotencyKey。
- CapabilityResult.message。
- 返回 data。
- 异常堆栈或数据库错误原文。

`auditResult` 的参数中根本没有 arguments 和身份细节。这比“先传入、再相信每个调用方记得脱敏”更安全。

## 6. requestId 为什么改成 requestRef

requestId 是外部可控字段。攻击者可以把它写成：

```text
request-1
FORGED outcome=SUCCESS password=secret
```

如果原样记录，换行可能伪造第二条日志；如果只删除换行，secret 仍然会落盘。

新实现使用：

```text
requestRef = SHA-256(requestId) 的前 16 个十六进制字符
```

这样具备两个性质：

1. 相同 requestId 会得到相同 requestRef，仍可关联多条日志。
2. 日志中不能直接还原 requestId、token、手机号或其他原文。

requestRef 只是排障引用，不是签名、认证凭据或防篡改机制。16 位截断也不适合作为数据库唯一键。

## 7. 其他标识怎样清洗

`McpAuditLogSanitizer.safeIdentifier` 按以下顺序处理：

1. 使用 `SensitiveTextRedactor` 遮蔽常见凭据、手机号、邮箱、证件号和长数字。
2. 只允许 Unicode 字母、数字以及 `.`、`-`、`_`、`:`、`/`。
3. 空格、换行、控制字符和其他标点统一替换为下划线。
4. 最多保留 96 个 Unicode code point。
5. 空值使用明确 fallback。

为什么按 code point 计数：

- Java `String.length()` 统计的是 UTF-16 code unit。
- 某些字符会占两个 code unit。
- 按 code point 截断可以避免把一个完整 Unicode 字符切成两半。

## 8. callerType 为什么不记录完整身份

当前只记录：

- `ANONYMOUS`。
- `MCP_CLIENT`。
- `ADMIN`。
- `AUTHENTICATED`。

普通运维日志通常只需要判断请求来自匿名调用、服务调用还是管理员调用。真实 userId、tenantId 和完整角色
集合会增加隐私暴露面，也可能被用于跨日志画像，因此不进入该日志。

如果未来安全团队需要按主体追责，应建立独立安全审计事件，并明确：

- 谁可以查询。
- 保存多久。
- 是否加密。
- 是否按租户隔离。
- 如何记录审计日志本身的访问。

## 9. 示例

成功调用：

```text
MCP audit serverId=smart-cs-local,
requestRef=54ee9cdef684e1bc,
capability=knowledge.search,
callerType=MCP_CLIENT,
provider=local-tool-registry,
outcome=SUCCESS,
errorCode=NONE,
replayed=false,
durationMs=2
```

缺少签名用户委托：

```text
MCP audit serverId=smart-cs-local,
requestRef=d4735e3a265e16ee,
capability=order.lookup,
callerType=MCP_CLIENT,
provider=mcp-server-adapter,
outcome=BUSINESS_REJECTED,
errorCode=MCP_DELEGATION_REQUIRED,
replayed=false,
durationMs=0
```

日志能说明“发生了什么”，但不能看到订单号、用户 ID、token 或请求参数。

## 10. 测试覆盖

### ApplicationLogSafetyTest

- 解析真实 `application.yml`。
- 断言 `log-requests=false`。
- 断言 `log-responses=false`。

### McpAuditLogSanitizerTest

- 超长标识最多 96 个 code point。
- 换行、Tab 和控制字符不会保留。
- password 和手机号先被脱敏。
- requestRef 稳定且不包含原 requestId。
- 空 requestId 返回 `none`。

### McpServerAuditLogTest

使用 Spring Boot 的 `OutputCaptureExtension` 捕获真实 SLF4J 输出，验证：

- SUCCESS 有审计。
- BUSINESS_REJECTED 有审计。
- TECHNICAL_FAILURE 有审计。
- 未认证拒绝有审计。
- 缺少用户委托有审计。
- 身份元数据冲突有审计。
- arguments、token、手机号、Prompt、userId、tenantId、conversationId 和 idempotencyKey 均不出现。
- 恶意换行不能伪造下一条日志。

全量结果：

```text
Tests run: 370
Failures: 0
Errors: 0
Skipped: 0
```

## 11. 新增 MCP 审计字段时的规则

新增字段前依次判断：

1. 排障是否真的需要这个字段？
2. 能否用枚举、计数、哈希引用代替原文？
3. 字段是否由外部控制？
4. 是否可能包含身份、凭据、业务参数或自由文本？
5. 是否有长度上限和日志注入测试？

以下字段不应加入普通审计：

- 用户消息。
- Prompt。
- Tool arguments。
- Capability data。
- JWT。
- 完整错误 message。
- 会话摘要。

## 12. 当前边界

本批不是完整安全审计平台，仍有以下边界：

1. Spring Security Filter 在进入 Controller 前产生的 401/403 由 `SecurityErrorWriter` 返回，目前没有统一
   写入本批 MCP 调用审计。
2. 审计仍输出到普通 SLF4J 后端，没有独立不可篡改存储、集中检索和告警。
3. 没有为日志定义保留期、租户隔离、查询权限和访问审计。
4. requestRef 使用普通 SHA-256 短哈希，只用于关联；若 requestId 可预测，不能把它当成匿名化身份。
5. 本批关闭模型原文日志，但真正发送给第三方 LLM/Provider 的数据最小化仍需独立设计。
6. 已经由旧版本写入外部日志平台的数据不会被新代码自动删除。
7. Gateway 之外的未知 RuntimeException 仍由上层异常边界处理；本批覆盖所有正常 `CapabilityResult`
   返回路径，不表示任何 JVM 崩溃都一定能留下审计日志。

## 13. 初学者阅读顺序

1. `application.yml`：先看模型日志为什么默认关闭。
2. `McpServerAdapter.invoke`：看不同结果怎样汇合到一个出口。
3. `McpServerAdapter.auditResult`：看普通日志实际允许哪些字段。
4. `McpAuditLogSanitizer.requestReference`：理解外部 requestId 为什么只留短哈希。
5. `McpAuditLogSanitizer.safeIdentifier`：理解脱敏、字符限制和长度限制。
6. `McpServerAuditLogTest`：最后从真实日志输出验证安全边界。

核心原则是：

> 最安全的日志脱敏不是“把所有数据传给 Logger 后再删除”，而是从方法签名开始就不让敏感数据进入日志链。
