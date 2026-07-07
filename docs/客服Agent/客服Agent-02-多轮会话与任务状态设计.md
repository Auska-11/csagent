# 智能客服 Agent：多轮会话与任务状态设计

## 1. 为什么需要多轮会话

客服场景天然不是单轮问答。

用户经常不会一次性把所有信息说完整，例如：

```text
用户：我要退货
Agent：请提供需要退换货的订单号，例如 ORD-004。
用户：ORD-004
Agent：请说明退货原因。
用户：质量问题
Agent：已为你提交退货申请。
```

如果系统只能处理单轮消息，那么第二轮用户只说 `ORD-004` 时，Agent 并不知道这是在补充退货订单号。

这会导致：

- Agent 无法继续上一轮任务。
- 用户必须每次都说完整句子。
- 退货、投诉、工单创建等任务无法自然完成。
- 项目看起来更像单轮聊天 Demo，而不是真正的客服 Agent。

因此，多轮会话是客服 Agent 的核心能力之一。

## 2. 参考项目给我们的启发

我们参考的 `ecommerce-customer-service-skill-mcp` 项目有一个很值得借鉴的设计：

```text
ChatRequest          当前这一轮请求
ConversationState    跨轮保存的会话状态
TurnContext          当前这一轮的执行上下文
```

这三个概念不能混在一起。

如果把当前用户消息、历史状态、工具观察结果都塞进 `ConversationState`，短期能跑，但后续会越来越乱。

更清晰的方式是：

- `ChatRequest`：表示“这一次用户发了什么”。
- `ConversationState`：表示“这个会话之前进行到哪里”。
- `TurnContext`：表示“这一轮执行过程中发生了什么”。

这也是我们后续要采用的方向。

## 3. 当前项目的问题

当前代码中，每次用户发消息都会创建一个新的 `ConversationState`。

当前流程大致是：

```text
ChatApiController
  ↓
CustomerServiceAgent.chat(message, userId)
  ↓
new ConversationState(message, userId)
  ↓
AgentRuntime.runTurn(state)
```

也就是说，每一轮都是一个全新的状态对象。

当前 `ConversationState` 里包含了：

- 当前用户消息 `message`
- 历史消息列表 `messages`
- 当前意图 `activeIntent`
- 工具观察结果 `observations`
- 任务状态 `taskState`
- 用户 ID `userId`

这里有两个问题。

第一个问题：状态不能跨轮保存。

请求结束后，这个 `ConversationState` 就丢失了。下一轮用户再发消息，系统不知道上一轮发生了什么。

第二个问题：当前输入和跨轮状态混在一起。

`message` 是当前这一轮的输入，而 `activeIntent`、`taskState`、历史消息才是跨轮状态。它们放在一个对象里，会导致职责不清。

因此后续需要拆清楚：

```text
当前输入      -> ChatRequest
跨轮状态      -> ConversationState
本轮执行过程  -> TurnContext
```

## 4. 第一版多轮会话目标

第一版多轮会话不追求复杂记忆系统，只解决一个核心问题：

> 让 Agent 能根据 `conversationId` 找回上一轮会话状态，继续完成当前任务。

第一版需要做到：

- 前端每次请求携带 `conversationId`。
- 后端根据 `conversationId` 读取 `ConversationState`。
- 如果没有旧状态，就创建新状态。
- 当前用户消息放在 `ChatRequest` 中。
- 跨轮任务进度放在 `ConversationState` 中。
- 当前轮工具结果和 LLM 结果放在 `TurnContext` 中。
- 每轮对话结束后，把 `ConversationState` 保存起来。
- 退货申请能支持多轮收集订单号和原因。

暂时不做：

- 数据库存储会话。
- Redis 会话缓存。
- 长期用户记忆。
- 自动摘要压缩。
- 跨设备会话同步。

这些可以放到后续阶段。

## 5. 核心对象设计

## 5.1 `ChatRequest`：当前请求

`ChatRequest` 表示当前这一轮用户请求。

建议包路径：

```text
src/main/java/com/smartcs/smart_cs_agent/agent/context/ChatRequest.java
```

字段设计：

```java
public class ChatRequest {
    private String conversationId;
    private String userId;
    private String message;
}
```

职责：

- 保存当前轮用户消息。
- 保存当前轮所属会话 ID。
- 保存当前用户 ID。

它不负责：

- 保存历史消息。
- 保存工具结果。
- 保存任务状态。

为什么要单独设计 `ChatRequest`？

因为“当前用户说了什么”和“会话之前进行到哪里”是两个概念。拆开后，代码会更容易理解。

## 5.2 `ConversationState`：跨轮状态

`ConversationState` 表示一个会话的长期状态，至少在当前聊天窗口内持续存在。

后续建议它保存：

```java
public class ConversationState {
    private String activeIntent;
    private List<Map<String, String>> recentMessages;
    private Map<String, Object> taskState;
    private String userId;
}
```

它负责保存：

- 当前正在处理的意图。
- 当前任务槽位。
- 最近历史消息。
- 用户 ID。

它不应该保存：

- 当前轮用户消息 `message`。
- 当前轮 LLM 结果。
- 当前轮工具观察结果。

这些应该放到 `ChatRequest` 或 `TurnContext`。

为什么这样设计？

因为 `ConversationState` 会跨多轮保存。如果它保存当前轮临时结果，就很容易把上一轮工具结果误当成当前轮结果。

## 5.3 `TurnContext`：本轮执行上下文

`TurnContext` 表示“当前这一轮 Agent 执行过程”。

建议包路径：

```text
src/main/java/com/smartcs/smart_cs_agent/agent/context/TurnContext.java
```

字段设计：

```java
public class TurnContext {
    private ChatRequest request;
    private ConversationState state;
    private List<Observation> observations;
    private int stepCount;
    private boolean done;
    private String finalAnswer;
}
```

它负责保存：

- 当前请求。
- 当前会话状态。
- 本轮执行过程中产生的观察结果。
- 当前轮执行步数。
- 当前轮是否已经结束。
- 当前轮最终回复。

为什么需要 `TurnContext`？

因为 Agent 执行一轮时，会发生很多临时事件：

- 计划了哪个 Action。
- 调用了哪个工具。
- 工具返回了什么。
- LLM 返回了什么。
- 是否已经得到最终回答。

这些内容只属于当前这一轮，不应该直接塞进跨轮的 `ConversationState`。

后续做 Trace 时，`TurnContext` 也会是最自然的承载位置。

## 5.4 `ConversationStore`：会话存储

`ConversationStore` 负责保存和读取 `ConversationState`。

建议包路径：

```text
src/main/java/com/smartcs/smart_cs_agent/agent/context/ConversationStore.java
```

第一版使用内存 Map：

```java
Map<String, ConversationState> conversations;
```

它需要提供几个方法：

```java
ConversationState getOrCreate(String conversationId, String userId);

void save(String conversationId, ConversationState state);

void clear(String conversationId);
```

注意这里不需要传 `message`。

原因是：

- 当前消息属于 `ChatRequest`。
- 会话状态只通过 `conversationId` 和 `userId` 创建或读取。
- 每轮执行结束后，再把更新后的状态保存回去。

第一版可以使用：

```java
private final Map<String, ConversationState> store = new ConcurrentHashMap<>();
```

为什么第一版用内存？

- 实现简单。
- 适合学习。
- 不引入数据库复杂度。
- 重启丢失可以接受，因为目前是简历项目和演示项目。

后续如果要升级，可以替换为：

- Redis
- MySQL
- 本地 JSON 文件

但对上层 Agent 来说，接口不需要大改。

## 6. 会话 ID：`conversationId`

`conversationId` 用来标识一段连续对话。

例如：

```text
USER-001 和 Agent 的第一个聊天窗口：conversationId = conv-001
USER-001 和 Agent 的第二个聊天窗口：conversationId = conv-002
USER-002 和 Agent 的聊天窗口：conversationId = conv-003
```

为什么不能只用 `userId`？

因为同一个用户可能开启多个会话：

- 一个会话咨询商品。
- 一个会话处理退货。
- 一个会话投诉售后。

如果只用 `userId`，不同任务会互相污染。

第一版可以让前端传 `conversationId`，如果前端没传，后端自动生成一个。

## 7. 任务状态与槽位

## 7.1 任务状态：`taskState`

`taskState` 用来保存当前任务执行到哪一步。

例如退货任务：

```text
activeIntent = return_apply
pendingSlot = order_id
order_id = null
return_reason = null
```

当用户补充订单号后：

```text
activeIntent = return_apply
pendingSlot = return_reason
order_id = ORD-004
return_reason = null
```

当用户补充原因后：

```text
activeIntent = return_apply
pendingSlot = null
order_id = ORD-004
return_reason = 质量问题
```

这时槽位已经收集齐，可以调用 `return_apply` 工具。

## 7.2 槽位：Slot

槽位可以理解成完成一个任务所需的信息。

不同任务需要不同槽位：

| 任务 | 必要槽位 |
| --- | --- |
| 查询订单 | `order_id` |
| 查询我的订单 | `user_id` |
| 商品搜索 | `keyword` |
| 申请退货 | `order_id`、`return_reason` |
| 查询退货进度 | `return_id` |
| 查询个人信息 | `user_id` 或 `token` |

如果槽位不完整，Agent 不应该乱调用工具，而是应该追问用户。

这就是槽位填充。

## 7.3 建议增加语义化方法

现在 `taskState` 是一个通用 Map，使用起来比较灵活，但新手读代码时不够直观。

第一版可以先继续使用 Map，但增加一些语义化方法：

```java
public void setPendingSlot(String slotName) {
    setTaskState("pending_slot", slotName);
}

public String getPendingSlot() {
    Object value = getTaskState("pending_slot");
    return value == null ? null : value.toString();
}

public void clearPendingSlot() {
    taskState.remove("pending_slot");
}
```

这样代码可读性会更好。

后续如果任务状态越来越复杂，再考虑定义：

```java
public class PendingTask {
    private String intent;
    private Map<String, Object> slots;
    private String pendingSlot;
}
```

但第一版不急着抽象，避免过度设计。

## 8. 一轮请求的新流程

后续一轮请求建议变成：

```text
ChatApiController
  ↓
构造 ChatRequest
  ↓
CustomerServiceAgent.chat(request)
  ↓
ConversationStore.getOrCreate(conversationId, userId)
  ↓
创建 TurnContext(request, state)
  ↓
AgentRuntime.runTurn(context)
  ↓
Skill 根据 context 决策
  ↓
Runtime 执行 Action
  ↓
更新 context 和 state
  ↓
ConversationStore.save(conversationId, state)
  ↓
返回 ChatResponse
```

这里有一个关键变化：

> Runtime 主链路不再只接收 `ConversationState`，而是接收 `TurnContext`。

当前第一版已经由 `CustomerServiceAgent` 内部组装 `TurnContext`，并让 Runtime 和 Skill 通过它读取当前输入和本轮观察结果。

## 9. 多轮退货流程设计

第一版重点支持退货申请流程。

因为退货流程最能体现多轮任务状态。

## 9.1 用户一次性提供完整信息

```text
用户：我想退 ORD-004，质量问题
```

Agent 应该：

1. 从 `ChatRequest.message` 读取用户消息。
2. 识别意图：`return_apply`。
3. 提取订单号：`ORD-004`。
4. 提取原因：`质量问题`。
5. 调用工具：`return_apply`。
6. 返回申请结果。

## 9.2 用户只说退货

```text
用户：我要退货
```

Agent 应该：

1. 识别意图：`return_apply`。
2. 发现缺少 `order_id`。
3. 在 `ConversationState` 中设置：

```text
activeIntent = return_apply
pending_slot = order_id
```

4. 返回：

```text
请提供需要退换货的订单号，例如 ORD-004。
```

## 9.3 用户补充订单号

```text
用户：ORD-004
```

Agent 应该：

1. 从 `ConversationState` 看到当前 `activeIntent = return_apply`。
2. 从 `ConversationState` 看到 `pending_slot = order_id`。
3. 从 `ChatRequest.message` 中提取订单号。
4. 保存：

```text
order_id = ORD-004
pending_slot = return_reason
```

5. 返回：

```text
请简单说明退货原因，例如质量问题、尺码不合适或不想要了。
```

## 9.4 用户补充原因

```text
用户：质量问题
```

Agent 应该：

1. 从 `ConversationState` 看到当前 `activeIntent = return_apply`。
2. 从 `ConversationState` 看到 `pending_slot = return_reason`。
3. 从 `ChatRequest.message` 读取原因。
4. 保存：

```text
return_reason = 质量问题
pending_slot = null
```

5. 调用工具：

```text
return_apply(order_id = ORD-004, reason = 质量问题)
```

6. 返回申请结果。

## 10. Skill 中的处理策略

在 `CustomerServiceSkill.nextAction()` 一开始，应该优先检查是否有未完成任务。

推荐顺序：

```text
1. 如果上一轮 LLM 已生成最终回复 → 返回 FinalAnswerAction
2. 如果上一轮工具已有结果 → 生成最终回复
3. 如果当前有 pending_slot → 处理用户补充信息
4. 再走规则意图识别
5. 最后走 LLM 意图识别
```

当前主链路已经从 `TurnContext` 读取当前输入：

```java
context.getMessage()
```

`ConversationState.message` 仍然暂时保留，主要是为了兼容旧入口和测试。

为什么 `pending_slot` 要排在规则识别前？

因为用户第二轮可能只说：

```text
ORD-004
```

这句话本身不包含“退货”两个字。

如果直接走规则识别，可能无法判断意图。

但如果先看 `pending_slot`，系统就知道：

> 当前正在等用户补充退货订单号。

这就是多轮任务状态的价值。

## 11. 状态清理规则

任务完成后，要清理临时状态。

例如退货申请成功后，需要清理：

```text
pending_slot
order_id
return_reason
response_generated
last_llm_purpose
last_capability
last_capability_result
```

但是不一定清理：

```text
activeIntent
recentMessages
userId
```

原因：

- `recentMessages` 是历史对话，需要保留。
- `activeIntent` 可以保留最近一次意图，便于调试。
- 任务槽位要清理，避免影响下一次任务。

第一版可以新增：

```java
public void clearTaskProgress() {
    taskState.remove("pending_slot");
    taskState.remove("order_id");
    taskState.remove("return_reason");
}
```

后续再把不同任务的清理逻辑做得更细。

## 12. 多轮会话与 Memory 的区别

这里容易混淆。

多轮会话状态不是长期记忆。

| 概念 | 保存内容 | 生命周期 | 用途 |
| --- | --- | --- | --- |
| 多轮会话状态 | 当前任务、槽位、短期历史 | 一个聊天窗口内 | 完成当前任务 |
| 短期记忆 | 最近对话摘要、用户临时偏好 | 当前会话内 | 帮助上下文理解 |
| 长期记忆 | 用户长期偏好、历史事实 | 跨会话 | 个性化服务 |

本阶段只做多轮会话状态。

例如：

```text
用户当前正在退货，已经提供了订单号，但还没提供原因。
```

这属于多轮会话状态。

而：

```text
用户喜欢蓝牙耳机。
用户经常购买数码配件。
```

这些才属于长期记忆。

## 13. 第一版接口设计

## 13.1 请求

```json
{
  "conversationId": "conv-001",
  "userId": "USER-001",
  "message": "我要退货"
}
```

如果前端不传 `conversationId`，后端可以生成：

```text
conv-{时间戳或 UUID}
```

## 13.2 响应

```json
{
  "conversationId": "conv-001",
  "userId": "USER-001",
  "reply": "请提供需要退换货的订单号，例如 ORD-004。",
  "timestamp": 123456789,
  "trace": [
    {
      "eventType": "ask_user",
      "step": 1,
      "message": "信息不足，向用户追问",
      "data": {
        "question": "请提供需要退换货的订单号，例如 ORD-004。"
      },
      "timestamp": 123456789
    }
  ]
}
```

## 14. 第一版验收用例

## 14.1 订单查询仍然可用

```text
用户：帮我查一下订单 ORD-001
期望：查询订单并返回订单状态
```

## 14.2 退货一次性完成

```text
用户：我想退 ORD-004，质量问题
期望：直接提交退货申请
```

## 14.3 退货多轮完成

```text
用户：我要退货
Agent：请提供需要退换货的订单号，例如 ORD-004。

用户：ORD-004
Agent：请简单说明退货原因。

用户：质量问题
Agent：已提交退货申请
```

## 14.4 不同会话互不影响

```text
conversationId = conv-a
用户：我要退货

conversationId = conv-b
用户：帮我查订单 ORD-001
```

期望：

- `conv-a` 仍然等待退货订单号。
- `conv-b` 正常查询订单。
- 两个会话状态互不污染。

## 14.5 同一用户多个会话互不影响

```text
USER-001 + conv-a：退货流程
USER-001 + conv-b：商品咨询
```

期望：

- 同一个用户可以同时存在多个会话。
- 不同会话的任务状态独立。

## 15. 面试表达

如果面试官问：“你是怎么支持多轮对话的？”

可以这样回答：

> 我没有只把历史消息塞进 prompt，而是把当前请求、跨轮状态和本轮执行上下文拆开设计。`ChatRequest` 保存当前用户消息，`ConversationState` 根据 conversationId 保存当前会话的 activeIntent、taskState 和最近消息，`TurnContext` 保存本轮 Action、Observation 和后续 Trace。这样当用户第一轮说“我要退货”，第二轮只说“ORD-004”时，系统能知道当前正在等待退货订单号，而不是把 ORD-004 当成一个孤立输入。

如果面试官问：“多轮会话和长期记忆有什么区别？”

可以这样回答：

> 多轮会话状态解决的是当前任务的连续性，例如当前退货流程已经收集了订单号，还差退货原因。长期记忆解决的是跨会话的用户偏好，例如用户喜欢某类商品。我的项目第一阶段先做会话级任务状态，保证客服任务能完成，后续再扩展长期记忆。

如果面试官问：“为什么不用数据库存会话？”

可以这样回答：

> 当前项目是简历演示和学习项目，第一版优先验证 Agent 多轮任务链路，所以使用内存 ConversationStore，降低复杂度。这个设计保留了 Store 接口，后续如果要支持持久化，可以替换为 Redis 或 MySQL，不影响上层 Agent 流程。

## 16. 开发任务拆分

建议按以下小步开发：

1. 新增 `ChatRequest`。已完成。
2. 新增 `ConversationStore`。已完成。
3. 调整 `ConversationState`。已完成第一版：
   - 逐步弱化当前 `message` 字段。
   - 增加 `recentMessages` 或继续复用 `messages` 保存历史。
   - 增加 `setPendingSlot()`、`getPendingSlot()`、`clearPendingSlot()`。
   - 增加 `clearTaskProgress()`，任务完成后清理临时槽位。
4. 新增 `TurnContext`。已完成第一版：
   - 持有 `ChatRequest` 和 `ConversationState`。
   - 保存本轮 observations、traceEvents、stepCount、done、finalAnswer。
5. 改造 `CustomerServiceAgent`。已完成第一版：
   - 注入 `ConversationStore`。
   - 新增 `chat(ChatRequest request)`。
   - 从 Store 读取状态，构造 TurnContext，执行后保存状态。
6. 改造 `ChatApiController`。已完成第一版：
   - 读取 `conversationId`。
   - 构造 `ChatRequest`。
   - 返回 `conversationId` 和 `trace`。
7. 改造 `CustomerServiceSkill`。已完成第一版：
   - 优先处理 `pending_slot`。
   - 主链路从 `TurnContext.getMessage()` 读取当前输入。
   - 支持退货订单号和原因多轮补充。
8. 增加多轮退货单元测试。已新增 `CustomerServiceSkillTest`。
9. 增加 Agent Trace 单元测试。已新增 `AgentRuntimeTraceTest`。
10. 手动测试多轮退货流程。已通过 Maven 测试验证核心逻辑。
11. 回头补充代码注释和文档复盘。持续进行。

## 17. 当前实现进度

目前已经完成多轮会话第一版核心链路：

- `ChatRequest`：承载当前轮输入，包括 `conversationId`、`userId`、`message`。
- `ConversationStore`：使用内存 `ConcurrentHashMap` 按 `conversationId` 保存会话状态。
- `ConversationState`：支持 `pending_slot`，并在新一轮开始时清理上一轮观察结果。
- `TurnContext`：承载当前请求、跨轮状态、本轮观察结果、执行轨迹、执行步数和最终回复。
- `TraceEvent`：记录本轮 Agent 执行轨迹。
- `ChatResponse`：统一返回 `conversationId`、`userId`、`reply`、`timestamp` 和 `trace`。
- `ChatApiController`：支持接收并返回 `conversationId`，并返回 trace。
- `CustomerServiceAgent`：新增 `chat(ChatRequest request)` 和 `chatWithTrace(ChatRequest request)` 入口。
- `CustomerServiceSkill`：优先处理 `pending_slot`，支持退货申请多轮槽位填充。
- `AgentRuntime`：追问用户时把追问内容写入历史，最终回复后清理任务临时进度，并记录 Trace。
- `CustomerServiceSkillTest`：覆盖退货申请多轮流程的核心判断。
- `AgentRuntimeTraceTest`：覆盖工具调用和 LLM 调用的 Trace 链路。

当前退货流程已经能表达为：

```text
用户：我要退货
Agent：请提供需要退换货的订单号，例如 ORD-004。

用户：ORD-004
Agent：已收到订单号 ORD-004。请简单说明退货原因，例如质量问题、不想要了或尺码不合适。

用户：质量问题
Agent：调用 return_apply(order_id = ORD-004, reason = 质量问题)
```

当前仍然保留一个过渡点：

- `ConversationState.message` 暂时保留，用来兼容旧入口和测试。
- `TurnContext.addObservation()` 会同步写入 `ConversationState`，方便旧逻辑过渡。
- TraceEvent 第一版已经完成，后续可以进一步弱化 `ConversationState` 中的本轮 observation。

当前已经在第一版多轮会话基础上继续升级了三级记忆：

- 短期记忆：`ConversationState.messages` 现在作为最近消息窗口使用。
- 任务记忆：通过 `ConversationMemorySnapshot` 对外展示当前意图、pendingSlot 和业务槽位。
- 摘要记忆：长会话超过窗口后会把较早消息压缩进 `summaryMemory`。
- Runtime 会记录 `memory_loaded` 和 `memory_compressed` Trace。
- 前端 Agent Inspector 已新增 Memory Tab。

详细设计见：

- [客服Agent-17-多轮记忆管理升级设计.md](客服Agent-17-多轮记忆管理升级设计.md)

## 18. 当前结论

多轮会话不是简单保存历史消息，而是要保存“当前任务做到哪一步”。

参考项目给我们的关键启发是：

```text
ChatRequest       当前输入
ConversationState 跨轮状态
TurnContext       本轮执行上下文
```

第一版的核心目标是：

- 用 `ConversationStore` 保存状态。
- 用 `conversationId` 区分会话。
- 用 `pending_slot` 表示当前等待用户补充的信息。
- 先把退货流程做成多轮闭环。

这个模块完成后，客服 Agent 会从“单轮工具调用”升级为“能持续完成任务的客服 Agent”。
