# 客服 Agent Capability 执行中 Deadline 与结果未知说明

## 1. 本批解决什么问题

改造前，`ExecutionContext` 已经有 `deadline`，`CapabilityDefinition` 也声明了 `defaultTimeoutMs`，但
Gateway 只在调用 Provider **之前**检查一次是否过期。一旦 Provider 已经开始执行，调用线程仍可能一直等；
而 `defaultTimeoutMs` 实际只是展示信息，没有约束执行。

这会产生两个风险：

1. 慢 Provider 长期占住 Web 请求线程，拖慢整个 Agent。
2. 副作用调用超时后被误认为“肯定失败”，再次换 key 重试可能创建重复退货或工单。

第十八批把 deadline 变成真实执行边界，同时保留“超时不等于副作用停止”的诚实语义。

## 2. 有效 Deadline 怎样计算

Gateway 为每次能力调用计算：

```text
有效 deadline = min(上游 ExecutionContext.deadline,
                    当前时间 + CapabilityDefinition.defaultTimeoutMs)
```

这样同时满足：

- 上游 Turn 只剩 100ms 时，能力不能再执行 5 秒。
- 上游给了 30 秒时，一个默认 2 秒的能力也不能擅自占满 30 秒。
- 收紧 deadline 时只复制 `ExecutionContext`，身份、tenant、conversation 和 idempotencyKey 原样保留。

Provider 收到的是收紧后的上下文，因此远程 MCP 或未来的新 Provider 也可以继续把剩余预算传给下游。

## 3. 为什么需要有界线程池

只调用 `Future.get(timeout)` 还不够。如果每次请求都新建线程，而下游又不响应中断，超时请求越多，
遗留线程就越多，最终 Agent 自己会耗尽资源。

`CapabilityInvocationExecutor` 同时限制：

- 最大工作线程数，默认 16。
- 最大等待队列，默认 64。

配置项：

```text
SMARTCS_CAPABILITY_EXECUTION_MAX_THREADS=16
SMARTCS_CAPABILITY_EXECUTION_QUEUE_CAPACITY=64
```

队列已满时不继续堆积，而是返回：

```text
CAPABILITY_EXECUTOR_SATURATED
```

这属于“Provider 确认没有开始”的技术失败，可以安全释放幂等占位。

## 4. 四种执行阶段必须区分

| 发生位置 | Provider 是否开始 | 返回语义 | 幂等记录 |
| --- | --- | --- | --- |
| 调用前 deadline 已到 | 否 | `CAPABILITY_DEADLINE_EXCEEDED` | 不创建/安全释放 |
| 任务只在队列中，等待时到期 | 否 | `CAPABILITY_DEADLINE_EXCEEDED` | 安全释放，可用同 key 重试 |
| 只读 Provider 已开始后到期 | 是 | `CAPABILITY_DEADLINE_EXCEEDED` | 不涉及副作用回放 |
| 副作用 Provider 已开始后到期 | 是 | `CAPABILITY_RESULT_UNKNOWN` | 保存并稳定回放，禁止自动重复执行 |

`CAPABILITY_RESULT_UNKNOWN` 仍属于 `TECHNICAL_FAILURE`，因为现有 Capability 契约保持成功、业务拒绝、
技术失败三态；稳定 `errorCode` 进一步表达“结果未知”，Workflow 和 Trace 不需要引入第四套结果模型。

## 5. 为什么 cancel(true) 也不能声称操作失败

`Future.cancel(true)` 只会向 Java 线程发送中断请求。以下情况都可能让真正的副作用继续发生：

- 第三方 SDK 忽略线程中断。
- HTTP 请求已经到达远端，远端正在提交事务。
- 数据库已经提交，但响应在网络中丢失。
- Provider 捕获中断后仍完成了清理或写入。

因此本批的保证是：**Agent 请求线程按时返回并尝试中断**，不是“强制杀死远端事务”。副作用已经开始时，
正确做法是按原 idempotencyKey 查询、回放或进入人工复核，而不是创建新 key 再试一次。

## 6. 与幂等 Store 怎样配合

### 第一个调用是 Owner

Owner 先取得内存/JDBC 幂等占位，再在有界执行器里调用 Provider：

- Provider 未开始就失败：抛出内部安全异常，让 Store 删除占位。
- Provider 已开始后超时：返回 `CAPABILITY_RESULT_UNKNOWN`，Store 将它保存为稳定结果。
- 后续相同 key：直接回放未知结果，Provider 调用次数仍为 1。

### 第二个调用正在等待 Owner

以前内存 Store 使用无限 `join()`，JDBC Store只使用自己的固定等待上限。现在两者还会接收调用方 deadline：

- deadline 先到时，第二个调用停止等待并返回 `CAPABILITY_DEADLINE_EXCEEDED`。
- 原 Owner 和它的租约不受影响，仍可继续完成并保存结果。
- 第二个调用绝不会绕过幂等层直接执行 Provider。

## 7. 关键代码阅读顺序

1. `CapabilityDefinition`：能力自己的默认超时契约。
2. `ExecutionContext.withDeadline`：只收紧时间，不改变可信身份字段。
3. `CapabilityExecutionGuard.boundExecutionContext`：计算较早 deadline。
4. `CapabilityInvocationExecutor`：有界线程和队列。
5. `CapabilityExecutionGuard.invoke`：等待、取消和结果分类。
6. `InMemoryCapabilityIdempotencyStore`：进程内同 key 等待边界。
7. `JdbcCapabilityIdempotencyStore`：跨实例等待边界和原租约保留。
8. `CapabilityDeadlineTest`：三种执行阶段的故障注入。

## 8. 自动化证据

新增 8 项默认测试：

1. 能力默认超时能终止请求等待。
2. 更早的上游 deadline 优先。
3. 收紧后的 deadline 会传给 Provider。
4. 已开始副作用超时保存并回放 `CAPABILITY_RESULT_UNKNOWN`。
5. 同 key 等待受调用方 deadline 约束。
6. 排队未开始时释放幂等占位，随后可安全重试。
7. 线程池饱和时 Provider 不会启动。
8. JDBC 跨 Store 等待同样受调用方 deadline 约束。

本批加入后默认测试从 397 增至 405，实际结果：

```text
Tests run: 405
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

## 9. 本批仍没有解决什么

1. Provider 如果忽略中断，后台线程仍可能继续运行到它自己的 I/O 超时，所以 HTTP/JDBC 超时不能删除。
2. `CAPABILITY_RESULT_UNKNOWN` 还缺统一的结果查询 API 和人工复核界面。
3. `HandoffActionHandler` 的专用人工转接链路不经过普通 Capability Gateway，但它已有独立 HTTP 超时和 Outbox。
4. LLM 调用仍缺覆盖重试次数的整轮总预算，这是下一批可靠性任务。
5. 有界线程池是基础 bulkhead，不等于完整的动态隔离、指标告警或按 Provider 分舱。
6. JDBC 幂等 Store 会在每次轮询之间检查 deadline，但一条已经发出的 SQL 仍由 JDBC 查询/Socket 超时约束；
   极短的 Capability deadline 可能被单次数据库 I/O 小幅或明显越过，后续可继续把剩余预算传给数据库层。

核心原则：

> Deadline 解决“调用方最多等多久”；幂等与结果查询解决“超时后副作用到底发生没有”。两者不能互相替代。
