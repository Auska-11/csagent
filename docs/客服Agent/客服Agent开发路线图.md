# 客服 Agent 开发路线图

> 本文档是 Smart CS Agent 后续开发的主计划。后续新增需求、调整优先级、准备简历和面试讲解，都优先参考这份文档。

## 1. 项目定位

Smart CS Agent 是一个面向电商客服场景的任务型 Agent 项目。

它不是一个简单的“大模型聊天页面”，而是一个具备以下能力的客服 Agent 系统：

- 能识别用户意图
- 能在信息不足时主动追问
- 能调用订单、商品、退换货、用户信息等业务工具
- 能基于客服知识库回答政策类问题
- 能保留多轮任务状态
- 能记录 Agent 每一步执行轨迹
- 能通过测试集评估端到端效果

最终希望在简历中表达为：

> 基于 Spring Boot + LangChain4j 实现电商客服 Agent，采用 Skill / Runtime / Tool 分层架构，将意图识别、工具调用、业务查询、知识库检索和回复生成解耦；支持多轮任务状态、工具调用 Trace、人工转接和端到端评测，提升客服场景下大模型应用的可控性和可观测性。

## 2. 当前项目基础

当前项目已经具备以下基础：

- Spring Boot Web 服务
- LangChain4j 模型调用配置
- `CustomerServiceAgent` 作为客服 Agent 入口
- `AgentRuntime` 作为执行循环
- `CustomerServiceSkill` 作为客服决策层
- `AgentAction` 抽象：
  - `LLMAction`
  - `CapabilityAction`
  - `AskUserAction`
  - `FinalAnswerAction`
- 业务工具：
  - `OrderTools`
  - `ProductTools`
  - `ReturnTools`
  - `UserTools`
- Repository 模拟数据层
- 静态前端聊天页面
- 初步 Skill 文档和客服参考资料

当前最主要的问题：

- 每轮请求都会新建 `ConversationState`，还不是真正的多轮对话。
- 当前输入、跨轮状态和本轮执行过程还没有拆开，后续需要引入 `ChatRequest`、`ConversationState`、`TurnContext` 三个概念。
- `CustomerServiceSkill` 职责偏重，后续需要逐步拆分。
- `ToolManager` 使用字符串和 `Map` 调工具，类型安全和错误处理还比较弱。
- 前端只能看到最终回复，看不到 Agent 执行过程。
- 还没有知识库检索、人工转接和评测集。

## 3. 参考项目吸收点

我们参考了两个本地项目和一个 EchoMind 项目描述。

### 3.1 ecommerce-customer-service-skill-mcp

值得借鉴：

- 清晰分层：
  - Agent Runtime 负责执行循环
  - Skill 负责客服策略
  - MCP Tool 负责业务能力
  - Knowledge Search 负责静态知识召回
- 请求、状态、上下文拆分清楚：
  - `ChatRequest` 表示当前这一轮输入
  - `ConversationState` 表示跨轮会话状态
  - `TurnContext` 表示本轮执行上下文
- `Observation` 统一包装每次 Action 的执行结果
- Trace 记录每一步执行过程
- 工具调用边界清楚
- Markdown 知识库组织完整
- 测试覆盖 Skill、Tool、Memory、Knowledge Search

我们不直接照搬：

- 不一开始就做完整远程 MCP Server。
- 不一开始引入过重的知识检索框架。
- 不把项目复杂度堆得超过当前学习阶段。

### 3.2 ecom-service-agent

值得借鉴：

- 技术演进路线清晰：
  - Prompt
  - 多轮对话
  - ReAct / 工具调用
  - MCP
  - RAG
  - Multi-Agent
  - Memory
  - Skill
  - Evaluation
- 有评测集 `cases.json`
- 有短期记忆和长期记忆设计
- 有 Multi-Agent 路由
- 有 LLM-as-Judge 的评测思路

我们不直接照搬：

- 不把所有能力一次性塞进项目。
- 不优先做复杂 Multi-Agent。
- 不为了显得高级而堆概念。

### 3.3 EchoMind 描述

EchoMind 提到的能力：

- 端到端意图识别：三路融合 + 加权投票
- MCP 工具调用：查询改写 + LLM 重排
- 多轮对话记忆：三级记忆 + 自动压缩
- 多 Agent 路由：意图路由 + 性能路由 + 降级
- Monitor 闭环监控：在线表现驱动路由权重调整
- 端到端评测：LLM-as-Judge 四维度打分

我们的取舍：

| 能力 | 是否纳入 | 当前策略 |
| --- | --- | --- |
| 意图识别 | 纳入 | 先做规则 + LLM，后续再做融合 |
| 工具调用 | 纳入 | 先做本地 ToolManager，再演进为 MCP-style |
| 多轮记忆 | 纳入 | 先做会话级任务状态，再做短期/长期记忆 |
| Multi-Agent | 二期纳入 | 先做单客服 Skill，后面拆售前/售后/投诉 |
| Monitor 闭环 | 暂缓 | 没有真实线上数据，先做 Trace 和统计 |
| LLM-as-Judge | 后期纳入 | 先做规则评测，再补 LLM Judge |

## 4. 开发原则

后续开发遵守以下原则：

1. 先做可运行闭环，再做高级抽象。
2. 每个阶段都要有可演示用例。
3. 每个技术点都要能在面试中讲清楚“为什么这么设计”。
4. 不堆概念，不照抄其他项目。
5. 优先做客服 Agent 的真实痛点：
   - 信息不足
   - 工具调用失败
   - 知识库召回不准
   - 用户情绪激烈
   - 多轮上下文丢失
6. 代码注释默认使用中文，帮助学习和复盘。

## 5. 阶段计划

## Phase 1：稳定基础客服 Agent 链路

目标：让当前客服 Agent 的基础场景稳定可用。

核心任务：

- 修复中文意图识别不稳定的问题。
- 订单、商品、退换货、用户信息四类场景跑通。
- 参数缺失时可以追问用户。
- 工具返回结果后，LLM 能生成中文客服回复。
- API key 使用环境变量配置，避免明文提交。

验收用例：

- `帮我查一下订单 ORD-001`
- `有什么产品推荐吗`
- `蓝牙耳机多少钱`
- `我想退 ORD-004 这个订单`
- `查一下 RET-001 的状态`
- `查看我的信息`

状态：进行中。

## Phase 2：多轮会话状态与槽位填充

目标：让 Agent 能处理信息不完整的多轮任务。

核心任务：

- 新增 `ChatRequest`，表示当前这一轮请求：
  - conversationId
  - userId
  - message
- 引入会话存储，例如 `ConversationStore`。
- 使用 `conversationId` 保存每个用户的会话状态。
- 调整 `ConversationState`，让它只保存跨轮状态：
  - 当前意图
  - 最近消息
  - 任务槽位
  - 等待用户补充的信息
- 新增 `TurnContext`，表示本轮执行上下文：
  - 当前请求
  - 当前会话状态
  - 本轮观察结果
  - 本轮最终回复
- 支持退货申请多轮流程。
- 支持订单查询多轮流程。

示例流程：

```text
用户：我要退货
Agent：请提供需要退换货的订单号，例如 ORD-004。
用户：ORD-004
Agent：请说明退货原因。
用户：质量问题
Agent：已为你提交退货申请。
```

验收标准：

- 用户第二轮只说 `ORD-004`，Agent 能知道上一轮是在退货流程。
- 信息缺失时不会乱调用工具。
- 槽位齐全后再执行工具。
- 会话状态可以按 `userId + conversationId` 区分。
- 当前输入和跨轮状态不会混在同一个字段里。

优先级：最高。

## Phase 3：Agent Trace 调用链

目标：让 Agent 不再是黑盒，前端和接口能看到每一步执行过程。

核心任务：

- 新增 `TraceEvent` 模型。
- 基于 `TurnContext` 记录本轮执行过程。
- 在 `AgentRuntime` 中记录：
  - turn_started
  - skill_selected
  - action_planned
  - tool_called
  - tool_observed
  - llm_called
  - final_answer
- API 返回 `reply` 的同时返回 `trace`。
- 前端调试面板展示 Trace。

示例 Trace：

```json
[
  {
    "event": "intent_detected",
    "details": {
      "intent": "RETURN_APPLY"
    }
  },
  {
    "event": "tool_called",
    "details": {
      "tool": "return_apply",
      "arguments": {
        "order_id": "ORD-004",
        "reason": "质量问题"
      }
    }
  }
]
```

验收标准：

- 每次对话能看到意图、动作、工具、参数、结果。
- 工具失败也能被记录。
- 前端调试面板可以展示执行链路。

优先级：高。

## Phase 4：工具调用标准化

目标：让工具调用更稳定、可扩展、可测试。

核心任务：

- 定义统一工具返回结构：

```java
public class ToolResult {
    private boolean success;
    private String toolName;
    private Object data;
    private String message;
    private String errorCode;
}
```

- 定义工具名称常量或枚举，避免字符串散落。
- 参数缺失时返回标准错误。
- 工具失败时给 Agent 明确的下一步建议。
- 将 `ToolManager` 从简单 `switch` 逐步升级为工具注册表。

验收标准：

- 所有工具返回统一结构。
- 工具调用失败不会导致空指针或异常直接暴露给用户。
- 新增工具时不需要大改 Runtime。

优先级：高。

## Phase 5：客服知识库与 RAG

目标：让政策类、FAQ 类问题基于知识库回答。

核心任务：

- 新建知识库目录，例如：

```text
knowledge/
├── policy/
│   ├── return_policy.md
│   ├── warranty_policy.md
│   └── invoice_policy.md
├── logistics/
│   └── delivery_policy.md
└── service/
    └── handoff_policy.md
```

- 实现 `KnowledgeTools`：
  - `knowledge_search`
- 第一版使用轻量本地检索：
  - 标题匹配
  - 关键词匹配
  - 正文片段匹配
- 后续再考虑向量检索、查询改写和 rerank。

示例问题：

- `超过七天还能退吗？`
- `发票什么时候能开？`
- `偏远地区可以配送吗？`
- `保修期多久？`

验收标准：

- 政策类问题必须调用知识库工具。
- 回复中不能编造知识库不存在的政策。
- 找不到知识时要说明未找到，并建议转人工或补充信息。

优先级：高。

## Phase 6：人工转接机制

目标：让 Agent 能识别自己不该处理或处理不了的场景。

核心任务：

- 新增 `HandoffAction`。
- 新增 `HandoffTools` 或 `TicketTools`。
- 定义转人工规则：
  - 用户主动要求人工
  - 强投诉或威胁维权
  - 涉及赔偿、敏感信息、复杂纠纷
  - 工具连续失败
  - Agent 多轮无法解决
- 生成转人工摘要。

示例：

```text
用户：你们这服务太差了，我要投诉！
Agent：非常抱歉给您带来不好的体验。我已为您转接人工客服，并附上当前问题摘要，方便人工客服继续处理。
```

验收标准：

- 投诉类问题能识别并转人工。
- 转人工时包含摘要：
  - 用户诉求
  - 已提供信息
  - 已调用工具
  - 建议人工处理原因

优先级：中高。

## Phase 7：端到端评测集

目标：用测试证明 Agent 能力，而不是只靠手动试。

核心任务：

- 新建 `eval_cases.json`。
- 每个 case 包含：
  - 用户输入
  - 期望意图
  - 期望工具
  - 期望关键词
  - 是否应该转人工
  - 是否多轮
- 编写评测 Runner。
- 输出指标：
  - 意图准确率
  - 工具命中率
  - 转人工准确率
  - 关键词覆盖率
  - 多轮流程完成率

示例 case：

```json
{
  "id": "return_apply_multi_turn",
  "turns": ["我要退货", "ORD-004", "质量问题"],
  "expectedIntent": "RETURN_APPLY",
  "expectedTools": ["return_apply"],
  "expectedKeywords": ["退货", "提交"],
  "expectedHandoff": false
}
```

验收标准：

- 至少 20 条评测用例。
- 可以一键运行评测。
- 输出可读报告。

优先级：中高。

## Phase 8：轻量 Multi-Agent 路由

目标：将客服能力拆成不同专家角色，体现复杂 Agent 编排能力。

核心任务：

- 新增三个 Skill：
  - `PreSalesSkill`：商品咨询、推荐、促销
  - `AfterSalesSkill`：订单、物流、退换货
  - `ComplaintSkill`：投诉、转人工
- `SkillManager` 根据用户消息选择 Skill。
- 第一版使用规则路由。
- 第二版使用规则 + LLM 路由。

验收标准：

- 商品问题进入售前 Skill。
- 订单和退货问题进入售后 Skill。
- 投诉问题进入投诉 Skill。
- Trace 中能看到命中的 Skill。

优先级：中。

## Phase 9：Memory 与自动压缩

目标：让 Agent 能记住用户偏好和历史摘要。

核心任务：

- 短期记忆：
  - 当前会话的最近消息
  - 当前任务槽位
- 长期记忆：
  - 用户偏好
  - 常用地址
  - 历史投诉倾向
  - 常咨询商品类型
- 自动摘要：
  - 长对话超过阈值时压缩成 summary

验收标准：

- 用户之前说过“我喜欢蓝牙耳机”，后续推荐商品时能参考。
- 长对话不会无限增长 prompt。
- 记忆内容可查看、可清空。

优先级：中。

## Phase 10：LLM-as-Judge 与 Monitor

目标：补充高级评测和监控能力。

核心任务：

- LLM-as-Judge 四维度评分：
  - 回答质量
  - 工具调用合理性
  - 是否忠实于工具/知识库结果
  - 是否符合客服语气
- Monitor 统计：
  - 工具成功率
  - 意图识别失败率
  - 转人工率
  - 平均响应时间
  - LLM 调用次数

注意：

这一阶段不急做。没有真实线上数据时，不要过度包装“在线闭环调权”。可以先做离线统计和评测报告。

优先级：中低。

## 6. MVP 范围

第一版 MVP 建议只包含以下内容：

- 基础客服场景稳定跑通
- `ChatRequest + ConversationState + TurnContext` 三对象拆分
- 多轮会话状态
- 退货申请槽位填充
- 工具调用 Trace
- 工具返回统一结构
- 简单知识库检索
- README 和演示用例完善

暂不包含：

- 完整 MCP 协议服务
- 复杂向量数据库
- 多 Agent 性能路由
- Monitor 动态调权
- 复杂长期记忆

## 7. 面试讲解主线

建议后续面试按照这条主线讲：

1. 为什么选客服 Agent：
   - 场景真实
   - 任务流程明确
   - 工具调用和多轮对话需求强

2. 整体架构：
   - Controller 接收请求
   - Controller 构造 `ChatRequest`
   - Agent 入口通过 `ConversationStore` 读取 `ConversationState`
   - Agent 入口创建 `TurnContext`
   - Skill 决策下一步 Action
   - Runtime 执行 Action
   - ToolManager 调用业务工具
   - LLM 整理最终回复

3. 关键设计：
   - 规则优先 + LLM 兜底
   - Skill / Runtime / Tool 解耦
   - 多轮槽位填充
   - Trace 可观测
   - 知识库检索防止模型乱答

4. 工程取舍：
   - 初期不用复杂 MCP，先做本地工具边界
   - 初期不用复杂向量库，先做可解释 Markdown 检索
   - 先做规则评测，再补 LLM-as-Judge

5. 项目深度：
   - 不只是聊天，而是完整客服任务执行系统
   - 有状态、有工具、有知识、有评测、有可观测性

## 8. 后续任务顺序

建议最近开发顺序：

1. 新增 `ChatRequest`，把当前输入从会话状态中拆出来。
2. 实现 `ConversationStore`，支持按 `conversationId` 保存多轮状态。
3. 调整 `ConversationState`，让它聚焦跨轮状态和任务槽位。
4. 新增 `TurnContext`，承载本轮请求、状态、观察结果和最终回复。
5. 改造 `CustomerServiceAgent` 和 `ChatApiController`。
6. 实现退货申请多轮流程。
7. 增加 `TraceEvent` 和接口返回 trace。
8. 前端调试面板展示 trace。
9. 定义 `ToolResult`，统一工具返回。
10. 新增 `KnowledgeTools` 和 Markdown 知识库。
11. 新增评测集和评测 Runner。

## 9. 暂缓事项

以下内容暂时不要急着做：

- Docker 部署
- MySQL 持久化
- 完整 MCP Server
- 多模型路由
- Monitor 动态调参
- 复杂 UI 重构

原因：

这些能力确实高级，但如果基础 Agent 链路、多轮状态、Trace 和评测没有做好，提前做这些会让项目变成“概念很多，但核心不稳”。

## 10. 当前结论

我们当前项目的方向是正确的。

下一步最重要的不是继续堆功能，而是把以下两个能力做扎实：

- 多轮任务状态
- Agent 执行 Trace

这两个能力做好后，项目就会从“客服 Demo”升级为“真正的任务型 Agent 系统雏形”。
