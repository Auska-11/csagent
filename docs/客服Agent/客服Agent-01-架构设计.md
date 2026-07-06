# 智能客服 Agent：架构设计

## 1. 为什么需要架构设计

客服 Agent 项目如果只做成“用户输入一句话，大模型回复一句话”，很容易变成一个普通聊天 Demo。

真正有价值的客服 Agent，需要解决的是一组工程问题：

- 用户表达不规范，系统要识别真实意图。
- 用户信息经常不完整，系统要主动追问。
- 订单、商品、退货等问题不能靠大模型编，需要调用真实业务工具。
- 退货政策、保修政策等问题需要查知识库，不能让模型凭空回答。
- 一次服务往往是多轮对话，系统要记住当前任务进展。
- Agent 调用了什么工具、为什么这么回答，需要能够被查看和调试。

因此，本项目采用分层架构，把“对话入口、Agent 执行、客服决策、业务工具、数据访问、知识资料”拆开。

这样做的目的不是为了把代码写复杂，而是为了让每一层职责清楚，后续新增多轮会话、Trace、RAG、人工转接和评测时，不会全部堆在一个类里。

## 2. 项目目标

本项目的目标是实现一个面向电商场景的任务型客服 Agent。

它需要支持：

- 订单查询
- 商品咨询
- 退换货申请
- 退换货进度查询
- 用户信息查询
- 信息不足时主动追问
- 工具调用后的中文客服回复
- 后续扩展知识库问答、人工转接和评测

项目最终希望体现的能力是：

> 不只是让大模型聊天，而是让 Agent 能根据用户意图选择合适的业务能力，完成真实客服任务。

## 3. 当前整体架构

当前项目主要分为以下几层：

```text
前端页面
  ↓
ChatApiController
  ↓
CustomerServiceAgent
  ↓
AgentRuntime
  ↓
CustomerServiceSkill
  ↓
ToolManager
  ↓
OrderTools / ProductTools / ReturnTools / UserTools
  ↓
Repository 模拟数据层
```

每一层的职责如下：

| 层次 | 当前类 | 核心职责 |
| --- | --- | --- |
| 前端页面 | `static/index.html` | 提供用户聊天界面和调试入口 |
| Controller | `ChatApiController` | 接收 HTTP 请求，调用 Agent |
| Agent 入口 | `CustomerServiceAgent` | 创建会话状态，调用 Runtime |
| 执行引擎 | `AgentRuntime` | 循环执行 Action，调用 LLM 或工具 |
| 决策层 | `CustomerServiceSkill` | 判断下一步应该做什么 |
| Action 抽象 | `AgentAction` 及子类 | 表达 Agent 的下一步动作 |
| 工具管理 | `ToolManager` | 根据工具名称分发到具体工具 |
| 业务工具 | `OrderTools` 等 | 查询订单、商品、退货、用户信息 |
| 数据访问 | `Repository` | 当前用内存 Map 模拟数据库 |

## 4. 目标架构借鉴

我们希望借鉴 `ecommerce-customer-service-skill-mcp` 项目的核心边界，而不是直接照搬代码。

它给我们的关键启发是：

```text
ChatRequest          当前这一轮请求
ConversationState    跨轮保存的会话状态
TurnContext          当前这一轮的执行上下文
ActionExecutor       统一执行 Action
Observation          统一记录 Action 执行结果
TraceEvent           记录 Agent 每一步执行轨迹
Capability           Skill 眼中的业务能力
Tool                 具体可执行工具
```

这套拆分的好处是：

- 当前输入、跨轮状态、本轮执行过程不会混在一起。
- Runtime 不沉淀具体客服规则。
- Skill 只负责客服策略和下一步 Action。
- 工具调用结果可以统一包装成 Observation。
- 后续做 Trace、评测和 MCP-style 工具边界会更自然。

因此，我们后续会逐步从当前简单架构演进到：

```text
ChatRequest
  ↓
ConversationStore 读取 ConversationState
  ↓
TurnContext(request + state)
  ↓
AgentRuntime
  ↓
Skill 产生 Action
  ↓
ActionExecutor 执行 Action
  ↓
Observation + TraceEvent
  ↓
保存 ConversationState
```

第一阶段不会一次性实现全部对象，但文档和后续代码设计会向这个方向靠拢。

## 5. 核心设计思想

## 5.1 Skill 负责决策，Runtime 负责执行

这是当前项目最重要的设计。

`CustomerServiceSkill` 不直接调用工具，也不直接调用大模型。它只负责判断下一步应该是什么 Action。

例如：

- 用户说“帮我查订单 ORD-001”
- Skill 判断这是订单查询
- Skill 返回 `CapabilityAction("order_lookup")`
- Runtime 看到这是工具 Action
- Runtime 调用 `ToolManager`
- ToolManager 调用 `OrderTools`

这样做的好处是：

- 决策逻辑和执行逻辑分开。
- 后续新增工具，不需要改 Runtime 的主流程。
- 后续新增 Skill，可以复用 Runtime。
- 调试时可以清楚看到 Agent 每一步做了什么。

可以简单理解为：

```text
Skill：我决定下一步做什么
Runtime：我负责把这一步执行掉
Tool：我负责具体业务能力
```

## 5.2 Action 是 Agent 的“动作语言”

当前项目有几类 Action：

| Action | 说明 |
| --- | --- |
| `LLMAction` | 需要调用大模型 |
| `CapabilityAction` | 需要调用业务工具 |
| `AskUserAction` | 信息不足，需要追问用户 |
| `FinalAnswerAction` | 已经得到最终回复，可以返回用户 |

为什么要设计 Action？

如果没有 Action，代码可能会变成这样：

```java
if (查订单) {
    直接调用工具;
    直接调用大模型;
    直接返回;
}
```

短期看简单，长期会导致：

- 决策、执行、回复混在一起。
- 很难记录执行轨迹。
- 很难支持多轮对话。
- 很难扩展新的 Agent 能力。

有了 Action 之后，Agent 的过程就变成：

```text
Skill 产生 Action
Runtime 执行 Action
执行结果写成 Observation
跨轮任务进度写回 ConversationState
Skill 根据新状态继续产生下一个 Action
直到 FinalAnswerAction
```

这就是一个简化版的 Agent observe-act 循环。

## 5.3 规则优先，LLM 兜底

客服场景里，有些意图非常明确：

- “查订单”
- “退货”
- “多少钱”
- “我的信息”

这些高频场景不需要全部交给 LLM 判断，可以先用规则识别。

当前 `CustomerServiceSkill` 的策略是：

1. 先用关键词规则判断常见意图。
2. 规则无法判断时，再调用 LLM 做语义识别。
3. LLM 识别出意图后，再转成具体 Action。

这样做的好处：

- 降低 LLM 调用成本。
- 高频场景响应更稳定。
- 避免大模型把明确问题识别错。
- 面试时可以讲清楚工程取舍。

面试表达：

> 我没有把所有决策都交给大模型，而是采用规则优先、LLM 兜底的混合路由方式。对于订单查询、退货申请等高频明确场景，用规则保证稳定性；对于用户表达比较模糊的情况，再使用 LLM 做语义理解。

## 6. 一轮对话的执行流程

以“帮我查一下订单 ORD-001”为例：

```text
1. 前端发送消息到 /api/chat/send
2. ChatApiController 构造 ChatRequest
3. CustomerServiceAgent 根据 conversationId 读取 ConversationState
4. CustomerServiceAgent 创建 TurnContext
5. AgentRuntime 获取当前 Skill
6. Skill 判断用户意图是订单查询
7. Skill 返回 CapabilityAction(order_lookup)
8. Runtime 或 ActionExecutor 调用 ToolManager
9. ToolManager 调用 OrderTools.queryOrderStatus()
10. 工具返回订单状态和物流信息
11. 执行结果包装成 Observation
12. Skill 看到工具结果，返回 LLMAction(response_generation)
13. Runtime 调用大模型生成中文客服回复
14. Skill 返回 FinalAnswerAction
15. Runtime 返回最终回复
16. CustomerServiceAgent 保存 ConversationState
17. Controller 返回给前端展示
```

这个流程体现了三个核心点：

- Skill 不直接执行，只做决策。
- Runtime 不关心客服业务，只执行 Action 或委托 ActionExecutor 执行。
- Tool 不关心话术，只返回业务结果。

## 7. 当前代码中的关键模块

## 7.1 `ChatApiController`

职责：

- 提供 HTTP 接口。
- 校验用户消息是否为空。
- 调用 `CustomerServiceAgent.chat()`。
- 返回回复、用户 ID 和时间戳。

当前不足：

- 返回内容只有最终回复。
- 后续需要返回 `conversationId` 和 `trace`。
- 请求体最好从 `Map` 改成明确的 DTO。

后续计划：

```java
public class ChatRequest {
    private String conversationId;
    private String userId;
    private String message;
}

public class ChatResponse {
    private String conversationId;
    private String reply;
    private List<TraceEvent> trace;
}
```

## 7.2 `CustomerServiceAgent`

职责：

- 作为客服 Agent 的业务入口。
- 当前版本直接创建 `ConversationState`。
- 调用 `AgentRuntime.runTurn()`。

当前不足：

- 每次请求都会新建 `ConversationState`。
- 所以目前还不是真正多轮对话。

后续计划：

- 引入 `ConversationStore`。
- 根据 `conversationId` 读取已有会话状态。
- 构造 `ChatRequest` 和 `TurnContext`。
- 当前轮执行结束后保存状态。

## 7.3 `AgentRuntime`

职责：

- 执行 Agent 主循环。
- 根据 Action 类型调用 LLM 或工具。
- 把执行结果写入状态。
- 防止死循环。

当前优点：

- 已经有清晰的 Action 执行循环。
- 已经区分 LLMAction、CapabilityAction、AskUserAction 和 FinalAnswerAction。

当前不足：

- 还没有 Trace。
- 工具调用异常没有统一处理。
- 超时提示还是英文。
- LLM 调用和工具调用缺少耗时记录。
- 当前 Runtime 直接执行 LLM 和工具，后续可以拆出 `ActionExecutor`。

后续计划：

- 引入 `TurnContext`，统一承载当前请求、会话状态和本轮观察结果。
- 引入 `Observation`，统一包装 Action 执行结果。
- 增加 `TraceEvent`。
- 每次 Action 计划、执行、观察都记录。
- 返回给前端调试面板。

## 7.4 `CustomerServiceSkill`

职责：

- 判断用户意图。
- 决定是否调用工具。
- 决定是否追问用户。
- 决定是否让 LLM 生成回复。

当前优点：

- 已经支持规则路由和 LLM 兜底。
- 已经覆盖订单、商品、退货、退货进度、用户信息、闲聊。
- 中文注释较多，适合学习。

当前不足：

- 职责偏重。
- 规则识别、参数提取、Prompt 构造、Action 编排都在一个类里。
- 后续意图变多后会变得难维护。

后续计划：

- 拆出 `IntentRouter`。
- 拆出 `SlotExtractor`。
- 拆出 `ResponsePromptBuilder`。
- `CustomerServiceSkill` 只负责整体编排。

## 7.5 `ToolManager`

职责：

- 接收工具名称和参数。
- 分发给具体业务工具。

当前优点：

- 简单直接，容易理解。
- 当前阶段适合学习。

当前不足：

- 使用字符串作为工具名，容易写错。
- 使用 `Map<String, Object>` 传参数，编译期无法检查。
- 工具返回结果没有统一结构。

后续计划：

- 定义工具名称常量或枚举。
- 定义统一 `ToolResult`。
- 参数缺失时返回标准错误。
- 逐步演进为工具注册表。

## 7.6 `Repository`

职责：

- 当前用内存数据模拟数据库。
- 为工具提供订单、商品、退货、用户信息查询。

当前优点：

- 不依赖数据库，方便演示和学习。
- 业务数据和工具逻辑分离。

后续计划：

- 第一阶段继续使用内存数据。
- 后续如有需要再替换为 MySQL。
- 面试中可以讲“Repository 层隔离了数据来源”。

## 8. 当前架构的优点

## 8.1 分层比较清楚

当前项目没有把所有逻辑写在 Controller 里，而是拆成：

- Controller
- Agent
- Runtime
- Skill
- Tool
- Repository

这已经具备后端项目的基本工程结构。

## 8.2 已经有 Agent 执行循环

`AgentRuntime` 中的循环非常重要。

它说明项目不是简单调用一次 LLM，而是支持：

- 思考下一步
- 执行动作
- 观察结果
- 再决定下一步

这是 Agent 项目的核心形态。

## 8.3 工具调用和回复生成分离

工具只返回业务数据，最终客服话术交给 LLM。

这样可以避免工具层承担话术职责，也方便后续统一调整客服风格。

## 8.4 已经具备后续扩展空间

当前架构可以继续扩展：

- 多轮会话
- Trace
- RAG 知识库
- 人工转接
- Multi-Agent
- 评测体系

这些都可以沿着现有架构继续加。

## 9. 当前架构的不足

## 9.1 还不是真正多轮对话

目前每次请求都会新建状态。

问题：

```text
用户：我要退货
Agent：请提供订单号
用户：ORD-004
```

第二轮时，系统不知道用户是在补充退货订单号。

解决方案：

- 新增 `ChatRequest` 表示当前输入。
- 引入 `ConversationStore`。
- 用 `conversationId` 保存状态。
- 在 `ConversationState` 中只记录跨轮任务和槽位。
- 后续新增 `TurnContext` 表示本轮执行过程。

## 9.2 当前输入和跨轮状态混在一起

当前 `ConversationState` 同时保存了当前消息、历史消息、任务状态和观察结果。

问题：

- 当前消息只属于这一轮。
- 任务状态需要跨轮保存。
- 工具观察结果只属于本轮执行过程。

解决方案：

```text
ChatRequest       当前输入
ConversationState 跨轮状态
TurnContext       本轮执行上下文
```

## 9.3 工具调用结果不标准

当前工具大多返回字符串。

问题：

- 成功、失败不好区分。
- 错误码无法统一处理。
- 前端无法结构化展示。
- Trace 中不好记录工具结果。

解决方案：

- 定义 `ToolResult`。
- 所有工具统一返回。
- LLM 生成回复时基于 `ToolResult.message` 和 `ToolResult.data`。

## 9.4 缺少可观测性

目前用户只能看到最终回复。

但我们不知道：

- Agent 识别了什么意图。
- 调用了哪个工具。
- 工具参数是什么。
- 工具是否失败。
- LLM 调用了几次。

解决方案：

- 增加 `TraceEvent`。
- Controller 返回 trace。
- 前端调试面板展示 trace。

## 9.5 缺少知识库

目前政策类问题只能靠模型回答。

例如：

```text
超过七天还能退吗？
发票什么时候开？
保修期多久？
```

这些问题应该查知识库，而不是让模型自由发挥。

解决方案：

- 新增 Markdown 知识库。
- 新增 `KnowledgeTools`。
- 第一版先做关键词检索。
- 后续再做向量检索和 rerank。

## 10. 后续目标架构

后续目标架构如下：

```text
前端聊天页面
  ↓
ChatApiController
  ↓
ChatRequest
  ↓
CustomerServiceAgent
  ↓
ConversationStore 读取会话状态
  ↓
TurnContext
  ↓
AgentRuntime
  ↓
SkillManager 选择 Skill
  ↓
CustomerServiceSkill / PreSalesSkill / AfterSalesSkill / ComplaintSkill
  ↓
Action
  ↓
ActionExecutor
  ↓
ToolManager / KnowledgeTools / HandoffTools
  ↓
Repository / KnowledgeBase / TicketSystem
  ↓
TraceEvent 记录全过程
  ↓
返回 ChatResponse(reply + trace)
```

第一阶段不会一次性实现全部内容，而是逐步演进。

## 11. 推荐开发顺序

结合当前代码情况，建议按以下顺序开发：

1. 多轮会话状态：
   - 新增 `ChatRequest`
   - 新增 `ConversationStore`
   - 新增 `TurnContext`
   - 支持 `conversationId`
   - `ConversationState` 只保存跨轮任务状态

2. 退货流程槽位填充：
   - 记录当前等待用户补充什么信息
   - 支持用户分多轮提供订单号和原因

3. Agent Trace：
   - 新增 `TraceEvent`
   - `TurnContext` 记录每一步动作和观察结果
   - API 返回 trace

4. 工具返回标准化：
   - 新增 `ToolResult`
   - 改造工具返回结构

5. 知识库 RAG：
   - 新增 `knowledge/` 目录
   - 新增 `KnowledgeTools`
   - 支持政策类问答

6. 人工转接：
   - 新增 `HandoffAction`
   - 支持投诉和复杂问题转人工

7. 评测体系：
   - 新增评测用例
   - 统计意图、工具、转人工和关键词覆盖

## 12. 面试表达

如果面试官问：“你这个客服 Agent 和普通聊天机器人有什么区别？”

可以这样回答：

> 普通聊天机器人通常是用户输入一句话，然后直接把上下文交给大模型生成回复。我的项目不是这样设计的。我把客服 Agent 拆成 Runtime、Skill、Action、Tool 和 Repository 几层。Skill 负责判断用户意图和下一步动作，Runtime 负责执行 Action，Tool 负责查询真实业务数据。这样系统不是单纯依赖大模型自由生成，而是能在订单查询、商品咨询、退换货等场景中调用确定性的业务能力，并且后续可以通过 Trace 看到 Agent 每一步执行过程。

如果面试官问：“为什么不全靠 LLM？”

可以这样回答：

> 客服场景里很多问题需要确定性，比如订单状态、退货进度、库存数量，这些不能靠模型猜。所以我采用规则优先、LLM 兜底的方式。高频明确场景先走规则和工具调用，表达模糊时再用 LLM 做语义识别。这样既降低成本，也提升可控性。

如果面试官问：“你现在项目最大的不足是什么？”

可以这样回答：

> 当前版本已经有 Agent Runtime、Skill 和工具调用链路，但多轮会话状态还不完整。下一步我会引入 ChatRequest、ConversationStore 和 TurnContext：ChatRequest 表示当前输入，ConversationState 保存跨轮任务状态，TurnContext 保存本轮执行过程。这样用户可以分多轮完成退货、查订单等任务，后续也能自然扩展 Trace。

## 13. 当前结论

当前项目架构方向是正确的。

它已经具备一个客服 Agent 的基本骨架：

- 有入口
- 有执行循环
- 有决策层
- 有 Action
- 有工具层
- 有模拟数据

接下来最应该优先补的是：

- 多轮会话状态
- Agent Trace
- 工具返回标准化

这三件事做好后，项目就会从“能跑的客服 Demo”升级成“有工程深度的任务型 Agent”。
