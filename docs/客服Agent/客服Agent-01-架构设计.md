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
- Agent 的核心行为需要能被评测集回归验证。

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
- 知识库政策问答
- 后续扩展人工转接和评测
- 离线路由评测

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
ToolRegistry
  ↓
OrderTools / ProductTools / ReturnTools / UserTools / KnowledgeTools / HandoffTools
  ↓
Repository 模拟数据层 / Markdown 知识库
```

每一层的职责如下：

| 层次 | 当前类 | 核心职责 |
| --- | --- | --- |
| 前端页面 | `static/index.html` | 提供用户聊天界面和调试入口 |
| Controller | `ChatApiController` | 接收 HTTP 请求，调用 Agent |
| Agent 入口 | `CustomerServiceAgent` | 创建会话状态，调用 Runtime |
| 执行引擎 | `AgentRuntime` | 循环执行 Action，调用 LLM 或工具 |
| 决策层 | `CustomerServiceSkill` | 判断下一步应该做什么 |
| 决策辅助 | `CustomerServiceSlotExtractor` | 从用户消息中抽取订单号、退货单号、退货原因、商品关键词 |
| 决策辅助 | `CustomerServiceIntentRules` | 集中维护政策问题、退货问题、转人工问题等关键词规则 |
| 决策辅助 | `CustomerServicePromptBuilder` | 构造意图识别、闲聊回复和工具结果总结 Prompt |
| Action 抽象 | `AgentAction` 及子类 | 表达 Agent 的下一步动作 |
| 工具门面 | `ToolManager` | 将 Runtime 的工具请求转换成标准 `ToolCall` |
| 工具注册 | `ToolRegistry` | 注册工具定义，校验参数，统一返回 `ToolResult` |
| 业务工具 | `OrderTools` 等 | 查询订单、商品、退货、用户信息 |
| 知识工具 | `KnowledgeTools` | 检索 Markdown 客服知识库 |
| 转人工工具 | `HandoffTools` | 创建模拟人工转接工单 |
| 数据访问 | `Repository` / `knowledge` | 当前用内存 Map 和 Markdown 模拟数据来源 |

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
| `HandoffAction` | 当前问题需要人工客服介入 |
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
2. ChatApiController 接收 ChatApiRequest
3. ChatApiController 构造 ChatRequest
4. CustomerServiceAgent 根据 conversationId 读取 ConversationState
5. CustomerServiceAgent 创建 TurnContext
6. AgentRuntime 获取当前 Skill
7. Skill 判断用户意图是订单查询
8. Skill 返回 CapabilityAction(order_lookup)
9. Runtime 或 ActionExecutor 调用 ToolManager
10. ToolManager 调用 OrderTools.queryOrderStatus()
11. 工具返回订单状态和物流信息
12. 执行结果包装成 Observation
13. Skill 看到工具结果，返回 LLMAction(response_generation)
14. Runtime 调用大模型生成中文客服回复
15. Skill 返回 FinalAnswerAction
16. Runtime 返回最终回复
17. CustomerServiceAgent 保存 ConversationState
18. Controller 返回给前端展示
```

这个流程体现了三个核心点：

- Skill 不直接执行，只做决策。
- Runtime 不关心客服业务，只执行 Action 或委托 ActionExecutor 执行。
- Tool 不关心话术，只返回业务结果。

## 7. 当前代码中的关键模块

## 7.1 `ChatApiController`

职责：

- 提供 HTTP 接口。
- 使用 Spring Validation 校验接口参数。
- 构造 `ChatRequest`。
- 调用 `CustomerServiceAgent.chatWithTrace()`。
- 返回 `ChatResponse`，包含回复、用户 ID、会话 ID、时间戳和 Trace。

当前已改进：

- 请求体已经从 `Map<String, String>` 改成明确的 `ChatApiRequest` DTO。
- `ChatApiRequest` 只负责接收前端 JSON，`ChatRequest` 只负责进入 Agent 主链路，两者职责分开。
- `ChatApiRequest` 已通过 `@NotBlank` 和 `@Size` 声明基础校验规则。
- 校验失败返回 `ApiErrorResponse`，包含 `errorCode`、`message` 和 `timestamp`。
- 已新增 `GlobalExceptionHandler`，统一处理 JSON 解析失败、参数校验失败、参数异常和未预期异常。

当前响应对象：

```java
public class ChatRequest {
    private String conversationId;
    private String userId;
    private String message;
}

public class ChatResponse {
    private String conversationId;
    private String userId;
    private String reply;
    private long timestamp;
    private List<TraceEvent> trace;
}
```

## 7.2 `CustomerServiceAgent`

职责：

- 作为客服 Agent 的业务入口。
- 通过 `ConversationStore` 读取或创建 `ConversationState`。
- 创建 `TurnContext`。
- 调用 `AgentRuntime.runTurn()`。
- 返回 `ChatResponse` 或兼容旧入口的 String 回复。

当前不足：

- 目前还是单一客服 Skill。
- 后续做 Multi-Agent 时，需要根据意图路由到不同 Skill。

后续计划：

- 保留当前 `CustomerServiceAgent` 作为统一入口。
- 后续新增售前、售后、投诉等 Skill，并由 `SkillManager` 做路由。

## 7.3 `AgentRuntime`

职责：

- 执行 Agent 主循环。
- 根据 Action 类型调用 LLM 或工具。
- 把执行结果写入状态。
- 防止死循环。

当前优点：

- 已经有清晰的 Action 执行循环。
- 已经区分 LLMAction、CapabilityAction、AskUserAction 和 FinalAnswerAction。
- 已经接入 `TurnContext` 和 `TraceEvent`。
- 已经记录 Action 计划、工具调用、LLM 调用和最终回复。
- 工具、LLM 和人工转接调用已经记录 `durationMs`。
- LLM 调用失败时会记录 `llm_failed`，并基于工具结果生成降级回复，避免接口直接 500。

当前不足：

- 当前 Runtime 直接执行 LLM 和工具，后续可以拆出 `ActionExecutor`。
- Trace 目前主要返回给前端调试，还没有持久化到文件或数据库。

后续计划：

- 后续拆出 `ActionExecutor`，让 Runtime 主循环更轻。
- 增加 Trace 持久化和聚合统计，服务后续 Monitor 与评测报告。

## 7.4 `CustomerServiceSkill`

职责：

- 判断用户意图。
- 决定是否调用工具。
- 决定是否追问用户。
- 决定是否让 LLM 生成回复。

当前优点：

- 已经支持规则路由和 LLM 兜底。
- 已经覆盖订单、商品、退货、退货进度、用户信息、闲聊。
- 已经支持政策类问题路由到 `knowledge_search`。
- 已经支持人工诉求、投诉和赔偿纠纷路由到 `HandoffAction`。
- 已经完成第一轮职责拆分：
  - `CustomerServiceSlotExtractor` 负责槽位抽取。
  - `CustomerServiceIntentRules` 负责关键词意图规则。
  - `CustomerServicePromptBuilder` 负责 Prompt 构造。
- 中文注释较多，适合学习。

当前不足：

- `CustomerServiceSkill` 仍然承担较多 Action 编排逻辑。
- 后续如果继续增加售前、售后、投诉等意图，单一 Skill 仍然会变长。

后续计划：

- 按业务域拆出 `PreSalesSkill`、`AfterSalesSkill`、`ComplaintSkill`。
- 或者继续拆出更细的 `IntentRouter`，让 `CustomerServiceSkill` 只负责整体编排。

## 7.5 `ToolManager`

职责：

- 作为 Runtime 调用工具层的门面。
- 将 `capability + arguments` 转换成 `ToolCall`。
- 委托 `ToolRegistry` 执行工具。
- 返回统一 `ToolResult`。

当前优点：

- 保留旧入口，Runtime 改动较小。
- 工具层已经具备 MCP-style 的调用边界。
- 工具执行结果可以结构化记录到 Trace。
- `ToolResult.data` 已经优先返回结构化数据，其中订单、商品、退货、用户和人工转接等业务工具已经升级为明确 DTO，便于评测和监控读取。
- `ToolDefinition` 已经可以导出 `inputSchema` 和 `outputSchema`，前端 Tools 面板可以直接展示工具入参和出参结构。

当前不足：

- 还没有拆成独立 MCP Server。
- 当前业务工具仍然使用内存模拟数据，还没有接真实数据库或外部服务。

后续计划：

- 继续补充嵌套输出 schema，让 `orders[]`、`products[]` 等列表字段也能描述元素结构。
- 后续将 `ToolRegistry` 抽到独立 MCP Server。

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

早期版本每次请求都会新建状态。

当前第一版已经通过 `ConversationStore` 按 `conversationId` 保存会话状态，
可以支持退货、订单查询这类多轮任务。

早期问题：

```text
用户：我要退货
Agent：请提供订单号
用户：ORD-004
```

早期版本第二轮时，系统不知道用户是在补充退货订单号。
当前已经通过 `ConversationStore + pending_slot` 解决第一版多轮任务状态。

解决方案：

- `ChatRequest` 表示当前输入。
- `ConversationStore` 按 `conversationId` 保存状态。
- `ConversationState` 记录跨轮任务和槽位。
- `TurnContext` 表示本轮执行过程。
- `TraceEvent` 记录每一步执行过程，并返回给前端调试控制台。

## 9.2 当前输入和跨轮状态混在一起

过渡阶段 `ConversationState` 仍然保留了当前消息和观察结果，用来兼容旧入口和旧逻辑。
但主链路已经开始拆分为 `ChatRequest + ConversationState + TurnContext`。

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

## 9.3 工具调用结果已完成第一版标准化

当前已经新增 `ToolResult`，工具层统一返回结构化结果。

已经解决：

- 成功、失败可以通过 `success` 区分。
- 失败原因可以通过 `errorCode` 记录。
- 工具名通过 `toolName` 记录。
- 给 LLM 的说明通过 `message` 提供。
- Trace 中可以记录 `success`、`errorCode` 和结果预览。

后续还可以增强：

- 继续补充返回 schema 和工具示例，让工具协议更接近真实 MCP `tools/list`。
- 将 `inputSchema` 继续增强为更完整的 JSON Schema。
- 将 `ToolRegistry` 拆成真正 MCP Server。

## 9.4 可观测性还可以继续增强

当前第一版已经支持 Agent Trace。

现在接口和前端调试控制台可以看到：

- 本轮何时开始。
- 选择了哪个 Skill。
- 计划了哪个 Action。
- 调用了哪个工具。
- 工具参数是什么。
- LLM 是否被调用。
- 最终回复是什么。

后续还需要增强：

- 按 Trace 统计平均耗时、工具失败率和转人工率。
- Trace 持久化和评测统计。

## 9.5 人工转接已完成第一版

当前已经新增 `HandoffAction` 和 `HandoffTools`。

支持场景：

- 用户明确要求人工客服。
- 用户投诉或情绪激烈。
- 涉及赔偿、补偿、维权、起诉等纠纷。

当前链路：

```text
CustomerServiceSkill
  ↓
HandoffAction
  ↓
AgentRuntime
  ↓
ToolManager / ToolRegistry
  ↓
HandoffTools
  ↓
返回人工工单号
```

当前 Trace 可以看到：

- `handoff_requested`
- `handoff_created`
- `handoff_failed`

后续可以增强：

- 接入真实工单系统。
- 使用 LLM 生成更完整的人工摘要。
- 工具连续失败时自动转人工。
- 评测转人工准确率。

## 9.6 知识库已完成第一版

早期版本政策类问题只能靠模型回答。

例如：

```text
超过七天还能退吗？
发票什么时候开？
保修期多久？
```

这些问题应该查知识库，而不是让模型自由发挥。

当前第一版已经完成：

- 新增 Markdown 知识库目录 `src/main/resources/knowledge`。
- 新增 `KnowledgeDocument` 和 `KnowledgeSearchResult`。
- 新增 `KnowledgeTools`。
- 新增 `knowledge_search` 工具。
- `CustomerServiceSkill` 会把政策类问题路由到知识库。
- 第一版使用标题、关键词和正文打分检索。

后续还可以增强：

- 扩充知识库文档。
- 增加召回评测集。
- 引入 query rewrite、embedding 检索和 rerank。

## 10. 后续目标架构

后续目标架构如下：

```text
前端聊天页面
  ↓
ChatApiController
  ↓
ChatApiRequest
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
   - 新增 `ChatRequest`。已完成。
   - 新增 `ConversationStore`。已完成。
   - 新增 `TurnContext`。已完成第一版。
   - 支持 `conversationId`。已完成第一版。
   - `ConversationState` 聚焦跨轮任务状态。已完成第一版。

2. 退货流程槽位填充：
   - 记录当前等待用户补充什么信息。已完成第一版。
   - 支持用户分多轮提供订单号和原因。已完成第一版。

3. Agent Trace：
   - 新增 `TraceEvent`。已完成第一版。
   - `TurnContext` 记录每一步动作和观察结果。已完成第一版。
   - API 返回 trace。已完成第一版。
   - 前端调试控制台展示 trace。已完成第一版。

4. 工具返回标准化：
   - 新增 `ToolResult`。已完成第一版。
   - 改造工具返回结构。已完成第一版。

5. 知识库 RAG：
   - 新增 `knowledge/` 目录。已完成第一版。
   - 新增 `KnowledgeTools`。已完成第一版。
   - 支持政策类问答。已完成第一版。

6. 人工转接：
   - 新增 `HandoffAction`。已完成第一版。
   - 支持投诉和复杂问题转人工。已完成第一版。

7. 评测体系：
   - 新增评测用例
   - 统计意图、工具、转人工和关键词覆盖

8. Skill 第一轮拆分：
   - `CustomerServiceSlotExtractor`。已完成第一版。
   - `CustomerServiceIntentRules`。已完成第一版。
   - `CustomerServicePromptBuilder`。已完成第一版。

9. Controller DTO：
   - 新增 `ChatApiRequest`。已完成第一版。
   - 将 HTTP 请求体和 Agent 内部请求对象分开。已完成第一版。
   - 新增 `ApiErrorResponse`。已完成第一版。
   - 新增 `GlobalExceptionHandler`。已完成第一版。
   - 新增 Spring Validation 参数校验和 MVC 层测试。已完成第一版。

## 12. 面试表达

如果面试官问：“你这个客服 Agent 和普通聊天机器人有什么区别？”

可以这样回答：

> 普通聊天机器人通常是用户输入一句话，然后直接把上下文交给大模型生成回复。我的项目不是这样设计的。我把客服 Agent 拆成 Runtime、Skill、Action、Tool 和 Repository 几层。Skill 负责判断用户意图和下一步动作，Runtime 负责执行 Action，Tool 负责查询真实业务数据。这样系统不是单纯依赖大模型自由生成，而是能在订单查询、商品咨询、退换货等场景中调用确定性的业务能力，并且可以通过 Trace 看到 Agent 每一步执行过程。

如果面试官问：“为什么不全靠 LLM？”

可以这样回答：

> 客服场景里很多问题需要确定性，比如订单状态、退货进度、库存数量，这些不能靠模型猜。所以我采用规则优先、LLM 兜底的方式。高频明确场景先走规则和工具调用，表达模糊时再用 LLM 做语义识别。这样既降低成本，也提升可控性。

如果面试官问：“你现在项目最大的不足是什么？”

可以这样回答：

> 当前版本已经有 Agent Runtime、Skill、MCP-style 工具调用层、多轮会话状态、Agent Trace、知识库 RAG、人工转接和四类离线评测第一版。我把当前输入、跨轮状态和本轮执行过程拆成 ChatRequest、ConversationState 和 TurnContext：ChatRequest 表示当前输入，ConversationState 保存跨轮任务状态，TurnContext 保存本轮 Observation 和 TraceEvent。工具层也拆成 ToolDefinition、ToolParameterDefinition、ToolCall、ToolResult 和 ToolRegistry，能统一参数校验、inputSchema、outputSchema、成功失败和错误码；订单、商品、退货、用户和人工转接工具输出也已经从 Map 升级成明确 DTO。前端 Agent Inspector 可以展示 Trace、过滤事件、复制 Trace JSON，并展示工具 input/output schema。Runtime 会记录工具、LLM 和转人工耗时，LLM 调用失败时会记录 `llm_failed` 并基于工具结果生成降级回复。接口层使用 ChatApiRequest、Spring Validation 和 GlobalExceptionHandler 保护 HTTP 边界。CustomerServiceSkill 也做了第一轮拆分，把槽位抽取、意图规则和 Prompt 构造分别拆到独立类里。政策类问题会走 KnowledgeTools 检索 Markdown 知识库；投诉、赔偿、用户要求人工等场景会走 HandoffAction 创建人工工单。评测体系目前覆盖路由、知识库召回、回复质量规则和 Agent 链路回复，并且会输出 JSON 指标报告、历史快照和 trend 趋势报告。当前最大的不足是 Agent 链路评测仍使用 Fake LLM，没有直接评估真实模型输出，下一步会补真实模型回复评测和 LLM-as-Judge。

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

- 真实 Agent 回复质量评测
- 工具 schema 继续标准化，后续演进为独立 MCP Server
- LLM-as-Judge
- 按售前、售后、投诉继续拆分 Skill

多轮会话、Agent Trace、前端 Agent Inspector、MCP-style 工具层、工具 input/output schema、业务工具 DTO、知识库 RAG、人工转接、LLM 失败降级、离线路由评测、知识库召回评测、回复质量规则评测、Agent 链路回复评测、评测报告 JSON 化、评测历史趋势、结构化工具数据、Skill 第一轮拆分、接口 DTO、参数校验和统一错误响应已经完成，后续重点是真实 Agent 回复质量评测、工具 schema 标准化和更细的业务域拆分。
