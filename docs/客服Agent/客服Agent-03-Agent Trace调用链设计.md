# 智能客服 Agent：Agent Trace 调用链设计

## 1. 为什么需要 Agent Trace

如果客服 Agent 只返回最终回复，系统就很像一个黑盒。

例如用户问：

```text
帮我查一下订单 ORD-001
```

前端只能看到：

```text
您的订单已发货，物流单号是 SF1234567890。
```

但我们不知道中间发生了什么：

- 识别到的意图是什么？
- Skill 计划了哪个 Action？
- Runtime 调用了哪个工具？
- 工具参数是什么？
- 工具返回了什么？
- 是否调用了 LLM？
- 最终回复是如何生成的？

Trace 的作用就是记录这些过程。

它的价值主要有三点：

1. 方便开发调试：出错时能看到问题发生在哪一步。
2. 方便面试讲解：证明项目不是普通聊天 Demo，而是有执行链路的任务型 Agent。
3. 方便后续评测：可以统计工具命中率、LLM 调用次数、失败原因等指标。
4. 方便性能分析：可以看到工具调用和 LLM 调用的耗时。

## 2. 当前实现目标

本阶段 Trace 不追求复杂监控平台，只做第一版可运行闭环：

- 新增 `TraceEvent` 模型。
- `TurnContext` 保存本轮 `traceEvents`。
- `AgentRuntime` 在关键步骤记录事件。
- 工具调用和 LLM 调用记录 `durationMs`。
- 新增 `ChatResponse`，让 API 同时返回 `reply` 和 `trace`。
- 前端调试控制台展示 trace。
- 单元测试验证工具调用和 LLM 调用链路能被记录。

暂时不做：

- Trace 持久化到数据库。
- 分布式链路追踪。
- OpenTelemetry。
- 线上监控面板。
- 自动统计报表。

这些都可以等核心 Agent 链路稳定后再扩展。

## 3. 核心对象设计

## 3.1 `TraceEvent`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/agent/context/TraceEvent.java
```

字段：

```java
public class TraceEvent {
    private String eventType;
    private int step;
    private String message;
    private Map<String, Object> data;
    private long timestamp;
}
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `eventType` | 事件类型，例如 `tool_called` |
| `step` | 当前是本轮第几步 Action |
| `message` | 给人看的简短说明 |
| `data` | 结构化细节，例如工具参数 |
| `timestamp` | 事件发生时间 |

## 3.2 `TurnContext.traceEvents`

`TurnContext` 表示“当前这一轮 Agent 执行过程”。

所以 Trace 放在 `TurnContext` 中最合适：

```java
private final List<TraceEvent> traceEvents;
```

原因：

- Trace 只属于当前这一轮，不应该长期塞进 `ConversationState`。
- `ConversationState` 更适合保存跨轮状态，比如 `pending_slot`、`order_id`。
- 每一轮请求结束后，`ChatResponse` 可以直接读取 `context.getTraceEvents()` 返回给前端。

## 3.3 `ChatResponse`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/agent/context/ChatResponse.java
```

字段：

```java
public class ChatResponse {
    private String conversationId;
    private String userId;
    private String reply;
    private long timestamp;
    private List<TraceEvent> trace;
}
```

为什么需要它？

早期 `CustomerServiceAgent.chat()` 只返回 `String`，只能表达最终回复。

现在我们需要返回更多信息：

```json
{
  "conversationId": "default-USER-001",
  "userId": "USER-001",
  "reply": "这是最终回复",
  "timestamp": 123456789,
  "trace": []
}
```

所以新增 `ChatResponse` 作为统一响应对象。

## 4. 当前记录的事件类型

当前第一版已经记录这些事件：

| 事件类型 | 触发位置 | 含义 |
| --- | --- | --- |
| `turn_started` | `AgentRuntime.runTurn()` 开始 | 开始处理本轮用户消息 |
| `skill_selected` | 获取当前 Skill 后 | 记录本轮使用哪个 Skill |
| `action_planned` | 每次 `Skill.nextAction()` 后 | 记录 Skill 计划的下一步 Action |
| `tool_called` | 工具调用前 | 记录工具名称和参数 |
| `tool_observed` | 工具调用后 | 记录工具返回结果预览和耗时 |
| `tool_failed` | 工具调用异常时 | 记录工具失败原因和耗时 |
| `llm_called` | LLM 调用前 | 记录 LLM 用途和 prompt 预览 |
| `llm_observed` | LLM 调用后 | 记录 LLM 返回结果预览和耗时 |
| `llm_failed` | LLM 调用异常时 | 记录 LLM 失败原因、耗时和降级路径 |
| `handoff_requested` | 判断需要转人工时 | 记录转人工原因和摘要 |
| `handoff_created` | 人工工单创建后 | 记录人工工单创建结果和耗时 |
| `ask_user` | 信息不足时 | 记录 Agent 追问用户的问题 |
| `final_answer` | 得到最终回复时 | 记录本轮最终回复 |
| `turn_timeout` | 超过最大循环次数时 | 记录执行步数超限 |

## 5. 一轮订单查询 Trace 示例

用户输入：

```text
帮我查一下订单 ORD-001
```

执行链路大致是：

```text
turn_started
skill_selected
action_planned: CapabilityAction(order_lookup)
tool_called: order_lookup(order_id = ORD-001)
tool_observed: 工具返回订单状态，记录 durationMs
action_planned: LLMAction(response_generation)
llm_called: response_generation
llm_observed: LLM 返回中文客服回复，记录 durationMs
action_planned: FinalAnswerAction
final_answer
```

这说明 Agent 不是直接让大模型自由回答，而是：

```text
识别任务 -> 调业务工具 -> 基于工具结果生成回复
```

## 6. API 响应示例

请求：

```json
{
  "conversationId": "conv-001",
  "userId": "USER-001",
  "message": "帮我查一下订单 ORD-001"
}
```

响应：

```json
{
  "conversationId": "conv-001",
  "userId": "USER-001",
  "reply": "您的订单 ORD-001 已发货，物流单号是 SF1234567890。",
  "timestamp": 123456789,
  "trace": [
    {
      "eventType": "tool_called",
      "step": 1,
      "message": "调用工具：order_lookup",
      "data": {
        "capability": "order_lookup",
        "arguments": {
          "order_id": "ORD-001"
        }
      },
      "timestamp": 123456789
    },
    {
      "eventType": "tool_observed",
      "step": 1,
      "message": "工具返回结果：order_lookup",
      "data": {
        "capability": "order_lookup",
        "durationMs": 3,
        "success": true,
        "errorCode": null,
        "messagePreview": "订单 ORD-001 当前状态为已发货..."
      },
      "timestamp": 123456790
    }
  ]
}
```

## 7. 前端展示方式

当前前端已经升级成一个轻量 Agent 工作台。

Trace 面板可以展示：

- 本轮事件数量。
- 最近一次工具调用。
- 最近一次工具或 LLM 耗时。
- 每个 `TraceEvent` 的事件类型、步骤号、说明和结构化 `data`。
- 按 `All`、`Tool`、`LLM`、`Handoff`、`Failed`、`Final` 过滤事件。
- 展示 Tool、LLM、失败事件和耗时摘要。
- 一键复制完整 Trace JSON，也可以复制单条 Trace 事件，方便排查问题或写评测样例。

Tools 面板可以展示：

- 当前注册工具列表。
- 工具说明。
- 必填参数。
- `inputSchema`。
- `outputSchema`。

这样做的好处是：

- 面试演示时可以直观看到 Agent 执行链路。
- 工具协议不是只写在后端代码里，而是能被前端直接消费。
- 评测和排查问题时，可以直接复制某一轮 Trace。

## 8. 测试设计

新增测试：

```text
src/test/java/com/smartcs/smart_cs_agent/agent/AgentRuntimeTraceTest.java
```

测试思路：

- 不调用真实 LLM。
- 使用 `FakeChatLanguageModel` 返回固定回复。
- 用真实 `ToolManager` 和 Repository 跑订单查询。
- 断言 Trace 中出现：
  - `turn_started`
  - `skill_selected`
  - `action_planned`
  - `tool_called`
  - `tool_observed`
  - `llm_called`
  - `llm_observed`
  - `final_answer`
- 断言 `tool_observed`、`llm_observed` 和 `handoff_created` 中包含 `durationMs`。

这样可以证明 Runtime 的执行链路确实被记录下来。

## 9. 面试表达

如果面试官问：“你怎么知道 Agent 每一步做了什么？”

可以这样回答：

> 我在 Runtime 层实现了 TraceEvent 机制。每一轮请求会创建 TurnContext，TurnContext 中保存本轮 traceEvents。Runtime 在开始处理、选择 Skill、计划 Action、调用工具、观察工具结果、调用 LLM、生成最终回复等关键节点都会写入 TraceEvent。工具、LLM 和人工转接工具还会记录 durationMs。接口返回 ChatResponse 时会同时返回 reply 和 trace，所以前端调试面板可以直接看到执行链路。这让 Agent 不再是黑盒，也为后续评测和监控打基础。

如果面试官问：“Trace 和日志有什么区别？”

可以这样回答：

> 普通日志主要给后端开发排查问题，通常写在控制台或文件里。TraceEvent 是面向 Agent 执行链路设计的结构化数据，会跟随每次接口响应返回。它不仅能给开发者看，也能给前端调试面板、评测系统使用。比如评测时可以直接检查某个 case 是否调用了期望工具。

## 10. 后续扩展

后续可以继续做：

1. Trace 持久化：
   - 保存到文件、数据库或评测报告
2. 评测系统接入：
   - 用 trace 判断工具命中率、LLM 调用次数、转人工是否正确
3. Trace 聚合报表：
   - 统计平均工具耗时、平均 LLM 耗时、失败率和转人工率
4. 工具结果继续结构化：
   - 当前核心业务工具已经升级为明确 DTO，并已暴露 outputSchema，后续可以补更完整的嵌套 schema

## 11. 当前结论

Agent Trace 第一版已经完成。

当前项目已经能做到：

- 用户看到最终回复。
- 开发者看到执行链路。
- 前端调试面板展示每一步事件，并支持 Trace 过滤、Trace 指标摘要、完整 Trace 复制和单条事件复制。
- 工具 Trace 记录 `success`、`errorCode`、`durationMs` 和结果预览。
- LLM Trace 记录 `purpose`、`durationMs`、结果预览和失败降级事件。
- 工具 `data` 已经具备结构化结果，核心业务工具已升级为明确 DTO，便于评测系统读取。
- Tools 面板可以展示和复制工具 `inputSchema` 与 `outputSchema`。
- 单元测试验证 Trace 链路。

后续最适合继续增强 Trace 持久化、聚合报表和真实回复质量评测，让执行链路不仅可见，也能被长期分析。
