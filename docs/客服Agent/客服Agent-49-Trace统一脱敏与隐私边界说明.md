# 客服Agent-49：Trace 统一脱敏与隐私边界说明

## 1. 本批次结论

Phase 8 第八批为公开 Trace 建立了统一安全边界：无论事件来自 `AgentRuntime`、内置 Action Handler，
还是以后新增的扩展 Handler，只要最终创建 `TraceEvent`，保存的就只能是经过脱敏、限长和深拷贝后的
安全快照。

核心规则可以概括为：

```text
业务执行数据：保持原值，继续交给 Workflow / Provider / LLM
可观测 Trace：在 TraceEvent 构造时统一生成安全副本
```

本批默认测试规模从 332 增加到 340。公开 Trace 的目标已经完成，但应用日志、MCP 服务端审计、
最近消息持久化，以及真正发送给第三方模型或 Provider 的数据最小化仍属于后续任务。

## 2. 原实现有什么风险

旧 `TraceEvent` 只是把调用方传入的 Map 原样保存：

```java
this.data = data == null ? Collections.emptyMap() : data;
```

这会产生两类问题。

第一类是隐私泄露：

- `turn_started.message` 保存完整用户消息。
- `llm_called.promptPreview` 可能保存原始 Prompt。
- `tool_called.arguments` 和 `action_planned.arguments` 会重复保存工具参数。
- `handoff_requested.summary` 会保存投诉原话和流程摘要。
- `final_answer.answer`、工具结果预览和异常原文也可能进入 Trace。
- Chat API 会把整条 Trace 直接序列化成 JSON 返回给前端。

第二类是历史可变：

```text
调用方创建 mutableMap
  -> new TraceEvent(..., mutableMap)
  -> 调用方继续修改 mutableMap
  -> 已经记录的历史 Trace 也跟着变化
```

这不只是代码风格问题。Monitor、Eval 和前端调试器看到的历史可能不再是事件发生时的真实快照。

## 3. 为什么把边界放在 TraceEvent

当前项目中，Trace 入口很多：

```text
AgentRuntime
ActionHandler
Workflow
Legacy Skill
未来第三方扩展 Handler
        |
        v
TurnContext.addTrace(...)
        |
        v
new TraceEvent(...)
```

如果在每个 Workflow 或 Handler 里分别脱敏，会出现三个维护问题：

1. 新增事件时容易忘记调用脱敏工具。
2. 不同模块会形成不同规则，同一个字段有时保留、有时遮蔽。
3. Runtime 会逐渐堆满订单、退货、人工工单等业务字段特判。

因此统一数据流改成：

```text
TurnContext.addTrace(eventType, message, rawData)
  -> TraceEvent
       -> sanitizeMessage(message)
       -> sanitizeData(rawData)
            -> 敏感键遮蔽
            -> 自由文本省略
            -> 其他字符串确定性脱敏
            -> 递归容量限制
            -> 不可变深拷贝
  -> ChatResponse / Monitor / Eval / 前端
```

`TraceEvent` 是不可绕过的领域边界。业务模块仍可以用简单的 `context.addTrace(...)`，不需要理解全部
隐私规则，也不需要在核心 Runtime 中注册业务字段。

## 4. 核心文件

| 文件 | 职责 |
| --- | --- |
| `observability/trace/TraceDataSanitizer.java` | 统一执行字段分类、递归脱敏、容量限制和深拷贝 |
| `agent/context/TraceEvent.java` | 在事件构造时强制生成安全快照 |
| `agent/context/TurnContext.java` | 所有运行时 Trace 的统一添加入口 |
| `agent/handler/core/HandlerTraceSupport.java` | 为 Handler 提供先脱敏、后截断的安全预览 |
| `security/SensitiveTextRedactor.java` | 识别凭据、邮箱、手机号、身份证和长数字等稳定格式 |

`AgentRuntime` 只把自己的摘要预览改为复用 `HandlerTraceSupport`，没有新增任何客服业务特判。

## 5. 字段怎样分类

### 5.1 敏感键直接遮蔽

以下类型的字段不应该显示部分值，统一返回：

```text
[REDACTED]
```

覆盖的常见键包括：

```text
password / passwd / pwd
secret / clientSecret / privateKey
apiKey
token / accessToken / refreshToken / bearerToken / idToken / sessionToken
authorization / authHeader
cookie / setCookie
credential
email / phone / mobile
idCard / identityNumber
bankCard / creditCard / cvv
密码 / 口令 / 密钥 / 令牌 / 手机 / 电话 / 邮箱 / 身份证 / 银行卡
```

键名会先转小写并移除下划线、横线等分隔符。因此 `api_key`、`api-key` 和 `apiKey` 会使用同一规则。

### 5.2 任意自由文本默认省略

以下字段可能包含任意用户原话，不能只依赖手机号或邮箱正则“碰巧识别到”：

```text
message / messages
prompt / prompts / promptPreview
query / queries
summary / summaries / summaryPreview
answer / answers / reply / replies
response / result / content / text
error / internalMessage / stackTrace
```

这些字段统一返回：

```text
[OMITTED]
```

省略而不是部分保留的原因是，自由文本可以包含姓名、地址、投诉细节或业务机密，而这些内容不一定有
稳定格式。Trace 需要告诉开发者“这里发生过一次调用”，不需要复制完整业务内容。

### 5.3 普通结构化字段继续保留

对排障有价值且风险较低的字段仍保留，例如：

```text
orderId = ORD-001
keyword = 蓝牙耳机
reason = COMPLAINT
success = true
durationMs = 12
messageCount = 4
errorCode = ORDER_NOT_FOUND
```

普通字符串仍会经过 `SensitiveTextRedactor`。因此一个叫 `note` 的字段即使没有被列为自由文本，只要值中
出现 `13800138000`、`buyer@example.com` 或 `token=secret`，这些稳定格式仍会被遮蔽。

## 6. 容量边界

Trace 是调试数据，也必须防止单个事件无限膨胀。

| 边界 | 当前上限 | 超过后的行为 |
| --- | ---: | --- |
| 事件展示 message | 256 个 Unicode code point | 安全截断并追加 `...` |
| 普通字符串值 | 512 个 Unicode code point | 安全截断并追加 `...` |
| Map key | 80 个 Unicode code point | 安全截断 |
| 单个 Map | 50 个条目 | 保留截断计数标记 |
| 单个集合或数组 | 50 个元素 | 最后一个元素保存截断计数标记 |
| 递归嵌套 | 8 层 | 返回 `[MAX_DEPTH_REACHED]` |

这里使用 Unicode code point，而不是 `String.length()`，避免从 UTF-16 代理对中间切坏 emoji。

当前上限是代码常量，适合现阶段确定性测试。以后如果真实生产 Trace 的规模差异很大，可以再抽成
`smartcs.observability.trace` 配置，但在没有真实需求前不增加额外配置复杂度。

## 7. 为什么必须先脱敏再截断

错误顺序：

```text
buyer@example.com
  -> 先截成 buyer@exam
  -> 邮箱正则无法识别
  -> 半个邮箱进入 Trace
```

正确顺序：

```text
buyer@example.com
  -> 先变成 [REDACTED]
  -> 再按长度限制
  -> Trace 中没有邮箱片段
```

`HandlerTraceSupport.preview()` 现在统一调用 `TraceDataSanitizer.safePreview()`。`AgentRuntime` 的摘要预览
也复用同一个方法，不再保留另一套先截断逻辑。

## 8. 递归、循环引用和不可变快照

Sanitizer 支持：

```text
Map
List / Set / 其他 Collection
对象数组
基本类型数组
多层嵌套组合
```

循环引用示例：

```java
Map<String, Object> data = new LinkedHashMap<>();
data.put("self", data);
```

如果直接递归会栈溢出。当前实现使用 `IdentityHashMap` 记录正在处理的容器对象，遇到当前路径上的同一
对象时返回：

```text
[CIRCULAR_REFERENCE]
```

所有 Map 和 List 结果都包装成不可变集合。原 Map、嵌套 List 或数组在事件创建后继续变化，不会影响
已经生成的 Trace。未知可变对象也不会原样挂入事件，而只记录类型标记：

```text
[OBJECT:SomeDomainType]
```

如果某个 DTO 的字段确实需要被观察，应先显式转换成有限的结构化 Map，而不是依赖可能泄露全部字段的
`toString()`。

## 9. 为什么业务数据仍保持原值

Trace 脱敏不能改变真实执行语义。

```text
CapabilityAction.arguments (原值)
  -> ToolCall / Capability Provider (原值)
  -> TraceEvent.data.arguments (安全副本)

LLMAction.prompt (原值)
  -> ChatLanguageModel (原值)
  -> TraceEvent.data.promptPreview ([OMITTED])
```

否则可能出现：

- 订单号被错误遮蔽后 Provider 无法查询。
- 用户明确提供的业务原因被占位符替换。
- Prompt 被修改后模型行为发生变化。
- 最终用户回复因为观测策略而缺失业务信息。

新的测试会同时验证“Provider/LLM 收到原值”和“序列化 Trace 不包含原值”，防止以后有人为了通过隐私
测试，直接在 Action 或请求对象上做破坏性修改。

## 10. 测试矩阵

| 测试 | 证明内容 |
| --- | --- |
| `TraceDataSanitizerTest` | 递归 Map/集合/数组、自由文本、敏感键、循环引用、深度、容量、Unicode 和不可变性 |
| `AgentRuntimeTraceTest` | 模型仍收到原始上下文，最终回复保持原值，整条 Trace JSON 不泄露用户消息或模型回复 |
| `CapabilityActionHandlerTenantTest` | Provider 收到原工具参数，Trace 只保存安全副本，普通 orderId 仍可观察 |
| `ActionHandlerRegistryTest` | 插件异常中的内部数据库口令不会进入 Trace |
| `EscalationWorkflowIntegrationTest` | 人工升级保留稳定 reason，但投诉原话和手机号不进入 Trace |
| `ChatApiControllerMvcTest` | 真实 HTTP JSON 序列化结果不包含 Prompt、手机号或 API key |
| `MonitorServiceTest` / `MonitorControllerTest` | 脱敏后 success、durationMs、errorCode 等指标字段仍可聚合 |

本批定向 57 项测试通过；最终全量默认套件 340 项通过。

## 11. 新增 Trace 字段时怎样判断

新增事件前按以下顺序判断：

1. 这是凭据、联系方式、证件或支付字段吗？如果是，加入敏感键规则并直接遮蔽。
2. 这是用户原话、Prompt、摘要、回复、错误原文吗？如果是，加入自由文本规则并默认省略。
3. 这是稳定 ID、枚举、计数、布尔值或耗时吗？可以保留。
4. 这是复杂 DTO 吗？只提取排障真正需要的有限字段组成 Map。
5. 新规则能否跨业务复用？如果只能识别某个订单或退货字段，应重新检查设计，不要把业务名称写进
   `TraceDataSanitizer`。

扩展 Handler 不需要手动调用 Sanitizer：

```java
context.addTrace("custom_event", "扩展 Handler 执行完成", data);
```

`TraceEvent` 会自动执行统一策略。手动预览只有在构造中间数据时才使用
`HandlerTraceSupport.preview()`，并且最终仍必须经过 `TraceEvent`。

## 12. 当前仍然存在的不足

1. 这不是加密。`[REDACTED]` 和 `[OMITTED]` 只是避免公开 Trace 暴露原文。
2. 姓名、地址、昵称和复杂账号等上下文型隐私仍可能漏报；确定性正则不能替代专业 DLP。
3. 实际发送给 LLM 或远程 Provider 的原始数据没有被本批修改。生产接入第三方服务时仍需数据最小化、
   用户告知、区域与保留期策略。
4. 应用日志、异常日志和 MCP 服务端审计没有自动经过 `TraceDataSanitizer`。
5. 最近消息窗口和已有持久化数据需要各自的保留期、访问控制和迁移策略。
6. 如果外部系统已经保存过旧版明文 Trace，新代码不会自动回写清理历史记录。
7. 自由文本默认省略会减少排障细节。生产环境更合理的补充方式是受权限控制的安全审计存储，而不是
   再把原文放回普通 ChatResponse。

因此第八批可以把“公开 Trace 默认不泄露原始 Prompt、用户消息全文和工具敏感参数”标记为完成，
但不能把整条系统隐私治理或 Phase 8 总目标标记为完成。

## 13. 初学者阅读顺序

建议按以下顺序阅读代码：

1. `TurnContext.addTrace()`：先看业务代码怎样添加事件。
2. `TraceEvent` 构造函数：看统一边界在什么时刻生效。
3. `TraceDataSanitizer.sanitizeData()`：看递归入口。
4. `sanitizeValue()`：理解字段分类和类型分发。
5. `sanitizeMap()`、`sanitizeCollection()`、`sanitizeArray()`：理解深拷贝和容量限制。
6. `HandlerTraceSupport.preview()`：理解先脱敏后截断。
7. `TraceDataSanitizerTest`：最后通过输入和期望输出验证自己的理解。
