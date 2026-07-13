# 客服Agent-61：LLM 重试上限与整轮时间预算说明

## 1. 本批解决什么问题

第十八批已经让 Capability 的 deadline 成为真实执行边界，但 LLM 链路仍有三个缺口：

1. `AgentRuntime` 只有最多 10 个 Action 的步骤预算，没有一整轮 wall-clock 总预算。
2. `LlmActionHandler` 直接同步调用 `chatModel.generate(...)`，模型 SDK 卡住时会长期占用请求线程。
3. LangChain4j 自带内部重试；如果业务层以后再随手增加一层重试，会形成“外层次数 × SDK 次数”的放大效应。

第十九批完成后，项目同时具备：

- OpenAI HTTP timeout；
- LangChain4j 内部总尝试次数显式上限（默认零重试）；
- 单个 LLM Action 等待上限；
- 一整轮 Agent wall-clock 总预算；
- LLM 有界线程池和排队容量；
- Capability/Handoff 对 Turn 剩余预算的继承；
- 稳定错误码、脱敏 Trace 和 Monitor 技术失败分类。

## 2. 四层预算分别负责什么

这四层不能互相替代：

| 层次 | 默认值 | 负责限制 | 不负责 |
| --- | ---: | --- | --- |
| Agent 步数预算 | 10 步 | 错误 Workflow 无限返回 `CONTINUE` | 单步卡住 |
| Turn 协作式总预算 | 30000 ms | 在关键检查点阻止本轮继续启动下一 Action | 强制杀死不遵守预算的同步 Handler |
| LLM Action 预算 | 12000 ms | 一次 `chatModel.generate(...)` 的排队和执行等待 | 多轮会话总时长 |
| OpenAI HTTP timeout | `PT10S` | 模型 SDK 的 HTTP 调用等待 | Runtime 里的其他 Action |

运行时取最严格的边界：

```text
LLM 实际等待时间
= min(LLM Action 预算, Turn 剩余预算)

Capability 实际 deadline
= min(Turn 剩余预算, Handler 30 秒上限, CapabilityDefinition 默认超时)
```

例如一轮默认还有 5 秒：

- LLM Action 即使配置为 12 秒，也只能再等约 5 秒；
- 默认超时 2 秒的 Capability 仍只执行 2 秒；
- Handler 不会重新给 Capability 一个新的完整 30 秒。

## 3. 为什么不在业务层再写重试

LangChain4j 0.35 的 OpenAI 模型本身支持 `max-retries`，但这个名字有误导性：它被直接传给
`RetryUtils.withRetry(..., maxAttempts)`，实际表示“最大总尝试次数”。因此本批把安全默认值显式固定为 1，
即总共只尝试一次、默认零重试；如果运维确认需要一次额外重试，应配置为 2。

默认不自动重试还有两个原因：0.35 的通用 RetryUtils 会重试普通 Exception，并在中间失败时记录异常；
项目目前没有按 401、限流、网络抖动等错误类型做精细分类。先失败降级，再由真实指标决定是否开启一次
额外重试，比默认重复所有错误更可控。

`LlmExecutionGuard` 不做第二套重试，只包住一次 `chatModel.generate(...)`。这样：

```text
正确：Runtime -> LlmExecutionGuard -> LangChain4j 有限内部重试 -> HTTP

避免：Runtime 外层重试
      -> Handler 外层重试
      -> LangChain4j 内部重试
      -> HTTP
```

如果三层都重试 3 次，请求量可能不是 3 次，而是成倍放大。除了增加延迟和费用，还会让限流故障更严重。

## 4. 核心实现

### 4.1 TurnContext：保存同一轮的绝对时间事实

`TurnContext` 新增以下方法：

- `startTurnBudget(timeoutMs)`：由 Runtime 在每次 `runTurn` 开始时启动预算；
- `remainingTurnBudgetNanos()`：给 LLM Guard 做高精度等待；
- `remainingTurnBudgetMs()`：给 Capability deadline 使用；
- `isTurnBudgetExceeded()`：Runtime 在关键边界判断是否停止；
- `getConfiguredTurnBudgetMs()`：写入 Trace，便于排障。

计时使用 `System.nanoTime()`，而不是 `Instant.now()`：

- `nanoTime` 只用于计算经过了多久；
- 它不受 NTP 校时、手工修改系统时间影响；
- 对外部协议需要绝对时间时，再把剩余毫秒换算成 `Instant` deadline。

未由 Runtime 启动预算的纯 Workflow 单元测试仍返回“无限剩余时间”。这样旧测试可以只验证状态机，
不需要为了时间机制启动完整 Spring 容器。

### 4.2 LlmExecutionGuard：有界等待和资源隔离

新增 `agent/llm/LlmExecutionGuard`，它负责：

1. 把同步模型调用提交到独立线程池；
2. 线程数固定有上限，默认 8；
3. 排队容量固定有上限，默认 32；
4. `Future.get(...)` 同时受 Action 预算和 Turn 余额限制；
5. 超时或请求线程中断时调用 `cancel(true)`；
6. 只返回稳定错误码，不把 SDK 异常原文交给 Trace；
7. Spring 关闭时通过 `@PreDestroy` 请求停止工作线程。

为什么线程池和队列必须有上限：

`cancel(true)` 只是向模型线程发送中断请求。第三方 SDK、网络库或自定义模型实现可能忽略中断，
因此 Java 不能承诺超时瞬间真正杀死线程。有界线程池保证即使出现这种情况，遗留调用也不会无限创建线程；
队列满后新调用快速返回 `LLM_EXECUTOR_SATURATED`。

### 4.3 LlmActionHandler：消费安全结果

Handler 不再直接处理 `Future`、线程池和模型 SDK 异常，只消费：

```text
LlmInvocationResult
|- success
|- text
|- errorCode
|- durationMs
`- budgetMs
```

成功时继续写入 `llm_response` Observation。失败时仍使用原有业务降级：

- 意图识别失败返回 `UNKNOWN`；
- 工具已经成功时，根据真实 `ToolResult` 生成确定性回复；
- 没有工具结果时返回模型暂时不可用提示。

`LLM_EXECUTION_INTERRUPTED` 是唯一的流程控制例外：Handler 记录安全降级 Observation 后返回 `FAILED`，
立即终止 Workflow，禁止在线程已被要求停止时继续启动 Capability 或其他副作用。

如果只是单个 LLM Action 的 12 秒预算到期、Turn 仍有时间，Workflow 可以使用降级结果继续；
如果耗尽的是 Turn 总预算，Runtime 会在 Handler 返回后停止下一步并把 Workflow 标记为 `FAILED`。

### 4.4 AgentRuntime：统一启动、检查和收尾

Runtime 在以下位置检查预算：

1. `runTurn` 入口启动 30 秒预算；
2. 每次循环准备开始前；
3. `Workflow.nextAction(...)` 返回后、外部 Action 启动前；
4. 一个要求 `CONTINUE` 的 Action 返回后、进入下一步前。

第 4 个检查很重要。某个扩展 Handler 即使没有遵守 deadline，而是在 60 秒后才返回，Runtime 也不会
因为“已经进入本次循环”再执行第二个 Action。

Turn 总预算耗尽时：

- 写入 `turn_deadline_exceeded`；
- 返回安全、稳定的用户提示；
- 将 Workflow 标记为 `FAILED`；
- 写入 `workflow_completed` 终态事件；
- Monitor 分类为 `TECHNICAL_FAILURE / TURN_DEADLINE_EXCEEDED`。

原 `turn_timeout` 事件继续只表示步骤预算耗尽，对应 `TURN_STEP_BUDGET_EXCEEDED`。两个概念没有混用。

### 4.5 Capability 与 Handoff：继承同一轮余额

`CapabilityActionHandler` 和 `HandoffActionHandler` 原来固定生成 30 秒上游 deadline。本批改为：

```java
long upstreamTimeoutMs = Math.min(
        DEFAULT_UPSTREAM_TIMEOUT_MS,
        turn.remainingTurnBudgetMs()
);
```

随后 CapabilityGateway 还会继续与能力自身 `defaultTimeoutMs` 取较早值。

这意味着一轮前面的 LLM 已经花掉 20 秒后，后面的工具不能重新获得 30 秒；所有 Action 真正共享同一个总预算。

### 4.6 Demo LLM 入口也不允许旁路

学习用 `/api/demo/chat` 原来直接调用 `chatModel.generate(...)`。本批也把它接入同一个
`LlmExecutionGuard`，因此项目中不再保留绕过 12 秒等待上限和有界线程池的直接模型入口。

## 5. 错误码与语义

| 错误码 | 含义 | Handler 行为 |
| --- | --- | --- |
| `LLM_TURN_BUDGET_EXCEEDED` | Turn 余额比单次预算更早耗尽 | 使用降级结果返回 Runtime，Runtime 停止后续步骤 |
| `LLM_CALL_DEADLINE_EXCEEDED` | 单个 LLM Action 达到自身等待上限 | 使用降级结果，Turn 有余额时可继续 |
| `LLM_EXECUTOR_SATURATED` | 线程和队列都已满 | 快速降级，不继续堆积 |
| `LLM_EXECUTION_INTERRUPTED` | 请求线程被中断或任务被取消 | 恢复中断标志、记录降级结果并返回 `FAILED`，不再执行下一 Action |
| `LLM_PROVIDER_ERROR` | 模型 SDK 抛出异常 | 不公开异常原文，稳定降级 |
| `LLM_EMPTY_RESPONSE` | 响应、内容或文本为空 | 视为技术失败并降级 |

这些错误码用于 Trace、Monitor 和测试。用户看到的是可理解的客服提示，不会看到 URL、token、Prompt、
模型响应片段或第三方异常堆栈。

## 6. Trace 变化

`llm_failed` 现在包含：

```json
{
  "purpose": "response_generation",
  "durationMs": 12001,
  "budgetMs": 12000,
  "errorCode": "LLM_CALL_DEADLINE_EXCEEDED"
}
```

不再写入：

```json
{
  "error": "第三方 SDK 原始异常 message"
}
```

`llm_observed` 增加 `budgetMs` 和 `degraded`，可以区分真实模型结果与降级结果。`turn_started` 增加
`turnTimeoutMs`，整轮耗尽时新增 `turn_deadline_exceeded`。

所有字段仍经过 `TraceEvent` 的统一脱敏、限长和不可变快照处理。

## 7. 配置项

### 7.1 Agent 与 LLM 执行器

| application.yml | 环境变量 | 默认值 |
| --- | --- | ---: |
| `smartcs.agent.max-iterations` | `SMARTCS_AGENT_MAX_ITERATIONS` | 10 |
| `smartcs.agent.turn-timeout-ms` | `SMARTCS_AGENT_TURN_TIMEOUT_MS` | 30000 |
| `smartcs.llm.execution.call-timeout-ms` | `SMARTCS_LLM_EXECUTION_CALL_TIMEOUT_MS` | 12000 |
| `smartcs.llm.execution.max-threads` | `SMARTCS_LLM_EXECUTION_MAX_THREADS` | 8 |
| `smartcs.llm.execution.queue-capacity` | `SMARTCS_LLM_EXECUTION_QUEUE_CAPACITY` | 32 |

### 7.2 LangChain4j OpenAI Client

| application.yml | 环境变量 | 默认值 |
| --- | --- | ---: |
| `langchain4j.open-ai.chat-model.timeout` | `OPENAI_TIMEOUT` | `PT10S` |
| `langchain4j.open-ai.chat-model.max-retries` | `OPENAI_MAX_RETRIES` | 1（最大总尝试次数，默认零重试） |

`.env.example` 和 `docker-compose.yml` 已同步这些参数，避免本地 JAR、测试配置和容器配置出现三套默认值。

## 8. 如何调整参数

推荐保持以下关系：

```text
HTTP timeout <= LLM Action timeout < Turn timeout
```

当前是：

```text
10 秒 <= 12 秒 < 30 秒
```

这样一次模型请求失败后仍有少量时间完成降级和 Runtime 收尾。调参时需要同时观察：

- 模型正常 P95/P99 延迟；
- 是否经常触发 SDK 重试；
- Web Server 请求超时；
- 上游网关或负载均衡器超时；
- LLM 线程池活跃数和拒绝数；
- 一轮通常需要几个 LLM/Capability Action。

不能只把 Turn 调大。如果上游网关 30 秒就断开，而 Agent 配置 60 秒，后 30 秒工作对用户没有价值。

## 9. 自动化测试证据

本批新增 13 项测试，默认套件从 405 增至 418：

1. 单个 LLM Action 到期后及时返回，并向 Fake Model 线程发送中断；
2. 两次模型调用共享 Turn 余额，第二次不能重新获得完整预算；
3. Provider 异常只返回 `LLM_PROVIDER_ERROR`，不携带敏感异常原文；
4. 慢 Action 返回后 Runtime 不再执行第二步；
5. 非正数 Turn 预算启动时失败；
6. Monitor 把 `turn_deadline_exceeded` 分类为技术失败；
7. 静态解析生产 YAML，锁定 OpenAI timeout、重试和两层 Agent 预算默认值；
8. 直接锁定 LangChain4j 0.35 的 `max-retries=1` 实际只产生一次总尝试；
9. Turn 已耗尽时模型调用次数保持为零；
10. LLM 工作线程和队列同时占满时稳定返回 `LLM_EXECUTOR_SATURATED`；
11. 混合 Trace 中整轮 deadline 覆盖前面的 LLM 组件级技术失败码；
12. 请求线程中断时 LLM Handler 返回 `FAILED`，不再进入下一 Action；
13. 学习用 `/api/demo/chat` 同样经过 Guard，并在模型失败时返回安全提示。

同时扩展已有测试，验证：

- `llm_failed` 不再暴露 `error` 原文；
- Capability/Handoff 的 deadline 被 Turn 剩余时间收紧；
- Docker Compose 暴露相同环境变量。

实际执行结果：

```text
bash mvnw -q clean test
Tests run: 418
Failures: 0
Errors: 0
Skipped: 0
```

真实 MySQL IT 属于第十七批独立验收，本批没有把它混入默认测试数字。

## 10. 当前仍然存在的边界

1. `Future.cancel(true)` 是协作式取消，不能强制杀死忽略中断的模型 SDK 线程；当前通过有界线程池控制资源上限。
2. 线程池指标尚未接入 Micrometer，暂时只能从错误码和 Trace 观察饱和。
3. 没有按租户、模型或 Workflow 做独立 bulkhead，大客户流量仍可能共享同一 LLM 池。
4. 默认总尝试次数为 1；配置为 2 后会依赖 LangChain4j 0.35 内置的延迟、退避和抖动，但项目尚未
   独立配置或观测每次重试，也没有跨请求的全局 retry budget 指标。
5. Runtime 能阻止慢 Handler 返回后继续，但普通自定义 Handler 若完全不使用 Guard，本次请求线程仍要等它实际返回。
6. 已经返回 `COMPLETED`、`HANDED_OFF` 等终态的 Action 会保留其结果，避免副作用实际成功后被改报失败；
   Turn deadline 主要阻止 `CONTINUE` 再启动下一步；扩展 Handler 自身必须读取 TurnContext 余额，
   并在调用下游能力时继续传播为 ExecutionContext deadline。
7. Web 容器、反向代理和客户端 timeout 仍需在真实部署环境与 30 秒 Turn 预算统一校准。

## 11. 面试讲法

### 为什么步骤上限不够

> 步骤上限只能解决逻辑死循环，不能解决一次模型调用卡住。我的实现同时保留 maxIterations 和
> wall-clock Turn budget，并把剩余时间向 LLM 与 Capability 传播，所以两种故障分别有明确边界。

### 为什么还要有界线程池

> Java 中断是协作式的，第三方 SDK 可能忽略 cancel(true)。所以 timeout 只保证请求线程不再等待，
> 有界线程池和队列才保证卡住的后台调用不会无限消耗资源。池满时系统快速返回稳定饱和错误码。

### 为什么业务层不重试

> LangChain4j 的配置名叫 max-retries，但 0.35 实际按最大总尝试次数解释。我把默认值设为 1，也就是
> 默认零重试；需要一次额外重试时可以设为 2。Runtime 和 Handler 不再套第二层，只负责预算、降级和观测。

### 你是否实现了强制终止

> 没有夸大。当前实现能保证调用方按 deadline 返回并请求中断，但不能保证第三方线程瞬间死亡。
> 因此我用资源上限控制最坏影响，后续还需要线程池指标、按租户 bulkhead 和真实模型压力测试。

## 12. 本批结论

第十九批关闭了 Phase 8 中“LLM 重试次数与整轮总预算”这一项：

- 重试上限成为显式配置；
- 业务层没有重复实现重试；
- 多个 LLM、Capability 和 Handoff Action 共享同一轮时间余额；
- 模型卡住时请求线程有明确返回上限；
- 忽略中断的后台任务受线程和队列容量限制；
- 错误码、Trace、Monitor、Docker 配置和自动化测试保持一致。

下一批优先处理售后结果查询与人工复核管理入口，继续收紧 Outbox 的人工处置闭环。
