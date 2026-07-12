# 客服 Agent 开发路线图

> 当前架构改造主计划：[`客服Agent-34-可扩展架构改造开发计划.md`](客服Agent-34-可扩展架构改造开发计划.md)
>
> 改造目标：分离 Agent Skill Package 与 Executable Workflow，引入可插拔 Action Handler 和 Capability Gateway/MCP Provider；以“新增业务 Skill 不修改 AgentRuntime、核心 Router 或 CapabilityGateway，并通过全量测试”为最终成功标准。

> Phase 7 当前进度：订单、退货查询、商品咨询和通用政策问答 Workflow 已完成，下一批迁移用户信息。
> 详细说明见 [`客服Agent-35-订单查询Workflow迁移说明.md`](客服Agent-35-订单查询Workflow迁移说明.md)
>、[`客服Agent-36-退货查询Workflow迁移说明.md`](客服Agent-36-退货查询Workflow迁移说明.md)
>、[`客服Agent-37-商品咨询Workflow迁移说明.md`](客服Agent-37-商品咨询Workflow迁移说明.md)
> 和 [`客服Agent-38-通用政策问答Workflow迁移说明.md`](客服Agent-38-通用政策问答Workflow迁移说明.md)。

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
- 能通过测试集评估路由、知识库召回、回复质量规则和 Agent 链路回复

最终希望在简历中表达为：

> 基于 Spring Boot + LangChain4j 实现电商客服 Agent，采用 Skill / Runtime / Tool 分层架构，将意图识别、工具调用、业务查询、知识库检索和回复生成解耦；支持多轮任务状态、工具调用 Trace、人工转接、接口参数校验、知识库召回评测、回复质量规则评测和评测趋势报告，提升客服场景下大模型应用的可控性、可观测性和可验证性。

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
  - `HandoffAction`
  - `FinalAnswerAction`
- 业务工具：
  - `OrderTools`
  - `ProductTools`
  - `ReturnTools`
  - `UserTools`
  - `KnowledgeTools`
  - `HandoffTools`
- Repository 模拟数据层
- 静态前端聊天页面
- 初步 Skill 文档和客服参考资料

当前最主要的问题：

- 已经引入 `ChatRequest`、`ConversationStore`、`TurnContext` 和 `TraceEvent`，Agent Trace 第一版已经落地，并已补充三级会话记忆第一版。
- `ConversationState.message` 仍作为兼容旧入口的过渡字段保留。
- `ConversationState.messages` 已升级为短期记忆窗口，较早消息会压缩进 `summaryMemory`。
- `CustomerServiceSkill` 已完成第一轮拆分：槽位抽取、意图规则和 Prompt 构造已经分别拆到 `CustomerServiceSlotExtractor`、`CustomerServiceIntentRules`、`CustomerServicePromptBuilder`。
- 工具层已经引入 `ToolDefinition`、`ToolParameterDefinition`、`ToolCall`、`ToolResult` 和 `ToolRegistry`，并已完成结构化 `data`、参数 `inputSchema` 和返回 `outputSchema` 第一版；订单、商品、退货、用户和人工转接等业务工具已经升级为明确 DTO，并新增 MCP Adapter 第一版，支持 `/api/mcp/tools` 和 `/api/mcp/tools/call`；当前还新增 `/mcp` JSON-RPC 风格入口，支持 `initialize`、`tools/list` 和 `tools/call`。
- 前端调试控制台已经升级为 Agent Inspector，支持典型 Demo 脚本、Trace 过滤、Trace 指标摘要、复制 Trace JSON、复制单条 Trace、工具搜索、展示和复制工具 inputSchema / outputSchema，以及 Eval 评测报告看板。
- 知识库检索第一版已经完成，并已补充 RAG 检索增强第一版：查询改写、多路召回、简单 rerank、本地轻量向量分、`knowledge_miss` Trace；当前还新增真 embedding + Qdrant 向量数据库可选模式，默认 local 保持测试稳定；知识库召回评测已扩展到 8 条。
- 人工转接第一版已经完成，并已新增售后工单平台联调第一版：默认 memory 模式生成模拟工单，开启 `SMARTCS_HANDOFF_MODE=after-sales-http` 后调用桌面 `demo/after-sales-sla-platform` 创建真实售后工单。
- 聊天接口已经完成明确 DTO、Spring Validation、统一错误响应和 MVC 层测试。
- Runtime 已支持 LLM 调用失败降级：模型异常时记录 `llm_failed`，并基于工具结果返回模板化客服回复。
- 离线路由评测、知识库召回评测、回复质量规则评测、Agent 链路回复评测和 LLM-as-Judge 第一版已经完成，并已支持 JSON 指标、历史快照、趋势报告和前端 Eval 看板；Monitor 第一版已经基于 Trace 聚合在线指标，支持按工具名查看成功率和耗时，并新增用户有用/无用反馈闭环、无用反馈 Eval 候选样本、候选样本问题类型分类、候选样本审核状态数量、Eval case 草稿导出、候选样本本地 JSON 持久化、Eval 草稿半自动合并正式评测文件和结构化风险告警；后续需要接入真实模型输出评测。

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
| 工具调用 | 纳入 | 已完成 MCP-style 第一版，后续再演进为独立 MCP Server |
| 多轮记忆 | 纳入 | 会话级任务状态、短期记忆、任务记忆、摘要记忆和长期商品偏好第一版已完成 |
| Multi-Agent | 二期纳入 | 先做单客服 Skill，后面拆售前/售后/投诉 |
| Monitor 闭环 | 纳入 | 已完成 Trace 指标、用户反馈、Eval 候选样本、问题类型分类、审核、草稿导出、本地 JSON 持久化和风险告警第一版，暂不包装动态调权 |
| LLM-as-Judge | 纳入 | 四维度评分第一版已完成，默认用 Fake Judge 保持测试稳定，并已支持显式开启真实模型 Judge 生成报告 |

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

状态：第一版已完成。当前基础客服链路已经能覆盖订单、商品、退货、用户信息、知识库和人工转接等核心场景，并已补充 LLM 失败时的基础降级回复。

## Phase 2：多轮会话状态与槽位填充

目标：让 Agent 能处理信息不完整的多轮任务。

核心任务：

- 新增 `ChatRequest`，表示当前这一轮请求。已完成：
  - conversationId
  - userId
  - message
- 引入会话存储，例如 `ConversationStore`。已完成第一版。
- 使用 `conversationId` 保存每个用户的会话状态。已完成第一版。
- 调整 `ConversationState`，让它只保存跨轮状态。部分完成：
  - 当前意图
  - 最近消息
  - 任务槽位
  - 等待用户补充的信息
- 新增 `TurnContext`，表示本轮执行上下文。已完成第一版：
  - 当前请求
  - 当前会话状态
  - 本轮观察结果
  - 本轮最终回复
- 支持退货申请多轮流程。已完成第一版。
- 支持订单查询多轮流程。已完成第一版。
- 增加 `CustomerServiceSkillTest` 覆盖退货多轮槽位填充。已完成。

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

状态：第一版核心链路已完成，并已接入 `TraceEvent`，可以把每一步 Action 和 Observation 暴露给接口和前端调试控制台。当前还新增了 Memory 快照接口和前端 Memory 面板，可以查看短期记忆、任务记忆和摘要记忆。

## Phase 3：Agent Trace 调用链

目标：让 Agent 不再是黑盒，前端和接口能看到每一步执行过程。

核心任务：

- 新增 `TraceEvent` 模型。已完成。
- 基于 `TurnContext` 记录本轮执行过程。已完成。
- 在 `AgentRuntime` 中记录。已完成第一版：
  - turn_started
  - skill_selected
  - action_planned
  - tool_called
  - tool_observed
  - llm_called
  - llm_observed
  - final_answer
- API 返回 `reply` 的同时返回 `trace`。已完成。
- 前端调试面板展示 Trace。已完成第一版。

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
- 工具、LLM 和人工转接工具调用耗时能被记录。
- 前端调试面板可以展示执行链路。

优先级：高。

状态：第一版已完成，并已接入 `ToolResult`。Trace 中现在可以看到工具是否成功、错误码、结果预览和 `durationMs`；LLM 与人工转接工具也已经记录耗时，LLM 异常时会额外记录 `llm_failed`。

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

- 新增 `ToolDefinition` 描述工具元信息。已完成第一版。
- 新增 `ToolCall` 表示标准工具调用请求。已完成第一版。
- 新增 `ToolResult` 统一工具返回结构。已完成第一版。
- 新增 `ToolRegistry` 集中注册和执行工具。已完成第一版。
- 参数缺失时返回标准错误。已完成第一版。
- 工具失败时给 Agent 明确的下一步建议。已完成第一版。
- 将 `ToolManager` 从简单 `switch` 逐步升级为工具注册表门面。已完成第一版。

验收标准：

- 所有工具返回统一结构。
- 工具调用失败不会导致空指针或异常直接暴露给用户。
- 新增工具时不需要大改 Runtime。

优先级：高。

状态：第一版已完成。当前工具层已经具备 MCP-style 的工具定义、参数 schema、调用请求、统一响应和注册表，并且订单、商品、退货、用户、知识库、人工转接工具已经优先返回结构化 `data`。其中订单、商品、退货、用户和人工转接等业务工具已经升级为明确 DTO，项目也新增了 `GET /api/tools` 只读调试接口，用来查看当前工具列表、`inputSchema` 和 `outputSchema`。后续可以继续补充嵌套 outputSchema，并进一步迁移成独立 MCP Server。

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
  - `knowledge_search`。已完成第一版。
- 第一版使用轻量本地检索：
  - 标题匹配。已完成第一版。
  - 关键词匹配。已完成第一版。
  - 正文片段匹配。已完成第一版。
  - 查询改写。已完成第一版。
  - 多路召回。已完成第一版。
  - 简单 rerank。已完成第一版。
- 召回失败 Trace：
  - `knowledge_miss`。已完成第一版。
- 已新增真 embedding + Qdrant 向量数据库第一版，后续再考虑文档切块、增量索引和 LLM rerank。

示例问题：

- `超过七天还能退吗？`
- `发票什么时候能开？`
- `偏远地区可以配送吗？`
- `保修期多久？`

验收标准：

- 政策类问题必须调用知识库工具。已完成第一版。
- 回复中不能编造知识库不存在的政策。已在检索文本中加入约束，后续评测继续验证。
- 找不到知识时要说明未找到，并建议转人工或补充信息。已完成第一版工具文本。

优先级：高。

状态：第一版和检索增强第一版已完成。当前已经新增 Markdown 知识库、`KnowledgeTools`、`KnowledgeDocument`、`KnowledgeSearchResult`，并把 `knowledge_search` 注册进 `ToolRegistry`。`CustomerServiceSkill` 已支持退换货政策、发票、保修、配送、转人工规则等问题的知识库路由。`KnowledgeTools` 现在支持查询改写、多路召回、简单 rerank，检索结果会返回命中词、命中字段、查询变体和排序原因；当知识库没有可靠命中时，Runtime 会记录 `knowledge_miss` Trace。当前还新增 `embedding-qdrant` 高级模式，支持调用 OpenAI-compatible embedding API 并使用 Qdrant 做向量检索，外部服务失败时会回退本地 RAG。

## Phase 6：人工转接机制

目标：让 Agent 能识别自己不该处理或处理不了的场景。

核心任务：

- 新增 `HandoffAction`。已完成第一版。
- 新增 `HandoffTools` 或 `TicketTools`。已完成第一版：当前实现为 `HandoffTools`。
- 定义转人工规则：
  - 用户主动要求人工。已完成第一版。
  - 强投诉或威胁维权。已完成第一版。
  - 涉及赔偿、敏感信息、复杂纠纷。已完成第一版中的赔偿/维权规则。
  - 工具连续失败
  - Agent 多轮无法解决
- 生成转人工摘要。已完成第一版规则摘要。

示例：

```text
用户：你们这服务太差了，我要投诉！
Agent：非常抱歉给您带来不好的体验。我已为您转接人工客服，并附上当前问题摘要，方便人工客服继续处理。
```

验收标准：

- 投诉类问题能识别并转人工。已完成第一版。
- 转人工时包含摘要：
  - 用户诉求
  - 已提供信息
  - 已调用工具
  - 建议人工处理原因

优先级：中高。

状态：第一版已完成。当前支持用户主动要求人工、投诉、赔偿/维权类表达触发 `HandoffAction`，由 Runtime 调用 `handoff_create` 创建模拟工单，并在 Trace 中记录 `handoff_requested` 和 `handoff_created`。

## Phase 7：端到端评测集

目标：用测试证明 Agent 能力，而不是只靠手动试。

核心任务：

- 新建 `eval_cases.json`。已完成第一版：`src/test/resources/eval/customer_service_eval_cases.json`。
- 每个 case 包含：
  - 用户输入。已完成。
  - 期望意图。已完成。
  - 期望工具。已完成。
  - 期望关键词。已在回复质量规则评测中完成第一版。
  - 是否应该转人工。已完成。
  - 是否多轮。已完成。
- 编写评测 Runner。已完成第一版：`CustomerServiceEvaluationTest`。
- 新增知识库召回评测。已完成第一版：`KnowledgeRecallEvaluationTest`。
- 新增回复质量规则评测。已完成第一版：`ResponseQualityEvaluationTest`。
- 新增 Agent 链路回复评测。已完成第一版：`AgentResponseEvaluationTest`。
- 输出指标：
  - 意图准确率。已完成。
  - 工具命中率。已完成。
  - 转人工准确率。已完成。
  - 知识库文档召回率。已完成。
  - 知识库关键词覆盖率。已完成。
  - 回复质量关键词覆盖和危险承诺拦截。已完成第一版。
  - Agent 链路最终回复质量和 Trace 事件覆盖率。已完成第一版。
  - 多轮流程完成率。第一版通过多轮 case 间接覆盖。
  - JSON 指标报告。已完成第一版，输出到 `target/eval-reports/`。
  - 历史快照和趋势报告。已完成第一版，输出 `history/` 和 `trend.json`。

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

- 至少 20 条评测样本。当前路由 16 条、知识召回 8 条、静态回复质量 6 条、Agent 链路回复 3 条、LLM-as-Judge 3 条，共 36 条评测样本。
- 可以一键运行评测。已完成，随 `mvn test` 运行。
- 输出可读报告。已完成控制台报告、JSON 指标报告，并新增 `docs/客服Agent/客服Agent-08-评测报告.md`。

优先级：中高。

状态：第一版已完成，并从单一路由评测升级为五类评测。当前路由评测 16/16 通过，知识库召回评测 8/8 通过，回复质量规则评测 6/6 判断一致，Agent 链路回复评测 3/3 通过，LLM-as-Judge 评测 3/3 判断一致。最近一次完整测试会随当前代码继续更新，并已输出五类 JSON 指标报告、历史快照和趋势报告到 `target/eval-reports/`。

## Phase 8：轻量 Multi-Agent 路由

目标：将客服能力拆成不同专家角色，体现复杂 Agent 编排能力。

核心任务：

- 第一版先新增路由层，不急着复制多个 Skill：
  - `pre_sales_agent`：商品咨询、推荐、价格、库存
  - `after_sales_agent`：订单、物流、退换货、保修、发票
  - `complaint_agent`：投诉、转人工、赔偿、维权
  - `general_agent`：通用兜底
- `SkillManager.routeAgent()` 根据用户消息选择专家域。已完成第一版。
- `AgentRuntime` 在 Trace 中记录 `agent_routed`。已完成第一版。
- 当前具体执行仍复用稳定的 `CustomerServiceSkill`，后续再拆真正独立 Skill。
- 第二版可升级为规则 + LLM 路由。

验收标准：

- 商品问题进入售前专家域。已完成第一版。
- 订单和退货问题进入售后专家域。已完成第一版。
- 投诉问题进入投诉专家域。已完成第一版。
- Trace 中能看到命中的专家 Agent。已完成第一版。

优先级：中。

状态：第一版已完成。当前没有强行拆多个重复 Skill，而是先完成路由层、专家域标签和 Trace 观测，后续要拆 `PreSalesSkill`、`AfterSalesSkill`、`ComplaintSkill` 时边界已经清楚。

## Phase 9：Memory 与自动压缩

目标：让 Agent 能记住用户偏好和历史摘要。

核心任务：

- 短期记忆：
  - 当前会话的最近消息。已完成第一版。
  - 当前任务槽位。已完成第一版。
- 任务记忆：
  - 当前意图。已完成第一版。
  - pendingSlot。已完成第一版。
  - 订单号、退货原因等业务槽位。已完成第一版。
- 摘要记忆：
  - 长对话超过阈值时压缩成 summary。已完成第一版规则压缩。
- 长期记忆：
  - 用户商品偏好。已完成第一版。
  - 常用地址
  - 历史投诉倾向
  - 常咨询商品类型

验收标准：

- 长对话不会无限增长 prompt。已完成第一版。
- 记忆内容可查看。已完成第一版：前端 Memory Tab。
- 用户之前说过“我喜欢蓝牙耳机”，后续推荐商品时能参考。已完成第一版。
- 记忆内容可清空。已完成第一版：支持清空会话记忆和用户长期记忆。

优先级：中。

状态：第一版已完成。当前 `ConversationState` 支持短期消息窗口、任务记忆快照和规则摘要记忆；`AgentRuntime` 会记录 `memory_loaded` 和 `memory_compressed` Trace；`UserMemoryStore` 支持按 userId 保存长期商品偏好；`ChatApiController` 提供会话记忆和用户长期记忆的查询/清除接口；前端 Agent Inspector 已新增 Memory Tab，并能展示和清除 User Long-Term Memory。

## Phase 10：LLM-as-Judge 与 Monitor

目标：补充高级评测和监控能力。

核心任务：

- LLM-as-Judge 四维度评分：
  - 回答质量。已完成第一版。
  - 工具调用合理性。第一版通过参考事实间接评估。
  - 是否忠实于工具/知识库结果。已完成第一版。
  - 是否符合客服语气。已完成第一版。
- Monitor 统计：
  - 工具成功率
  - 意图识别失败率
  - 转人工率
  - 平均响应时间
  - LLM 调用次数
  - 结构化风险告警
- 用户反馈：
  - 有用反馈数
  - 无用反馈数
  - 有用反馈率
  - 无用反馈进入 Eval 候选池

注意：

这一阶段不急做复杂动态调权。没有真实线上数据时，不要过度包装“在线闭环调权”。当前已先做离线统计、评测报告、Monitor 指标、用户反馈闭环、Eval 候选样本、本地 JSON 持久化、Eval 草稿半自动合并和 Fake Judge 版本的 LLM-as-Judge。

优先级：中低。

状态：LLM-as-Judge 第一版已完成并补充真实 Judge 可选入口。当前新增 Judge Prompt、结构化 JSON 解析、Fake Judge 测试样例、`llm-as-judge.json` 报告和 `scripts/run-real-judge-eval.ps1` 手动评测脚本；默认 `mvn test` 仍使用 Fake Judge 保持稳定，显式开启后可调用真实 OpenAI-compatible 模型生成 Judge 报告。Monitor 已能把无用反馈生成 `FeedbackEvalCandidate` 待审核样本，支持审核为 `APPROVED` / `REJECTED`，支持导出审核通过样本为 Eval case 草稿，并已通过本地 JSON 文件保存候选样本；人工补齐断言后，`APPROVED` 草稿可以半自动合并到正式 `response_quality_eval_cases.json`；Monitor Summary 已能根据工具失败、LLM 失败、低有用反馈率和高风险负反馈生成结构化风险告警。

## Phase 11：Docker 与部署工程化

目标：让项目具备可复现运行能力，方便面试演示和后续 CI/CD 扩展。

核心任务：

- 多阶段 Docker 构建。已完成第一版。
- `docker-compose.yml` 一键启动。已完成第一版。
- `.env.example` 示例配置。已完成第一版。
- 模型 API Key、baseUrl、模型名通过环境变量注入。已完成第一版。
- `/api/health` 健康检查接口。已完成第一版。
- README 和部署文档补充启动步骤。已完成第一版。

状态：第一版已完成。当前可以通过 `docker compose up --build` 启动服务，并访问 `http://localhost:8080` 和 `http://localhost:8080/api/health` 验证服务状态。后续可以继续补 CI 镜像构建、生产 profile 和日志目录挂载。

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

1. 新增 `ChatRequest`，把当前输入从会话状态中拆出来。已完成。
2. 实现 `ConversationStore`，支持按 `conversationId` 保存多轮状态。已完成。
3. 调整 `ConversationState`，让它聚焦跨轮状态和任务槽位。已完成第一版。
4. 改造 `CustomerServiceAgent` 和 `ChatApiController`。已完成第一版。
5. 实现退货申请多轮流程。已完成第一版。
6. 新增 `TurnContext`，承载本轮请求、状态、观察结果和最终回复。已完成第一版。
7. 增加 `TraceEvent` 和接口返回 trace。已完成第一版。
8. 前端调试面板展示 trace。已完成第一版。
9. 定义 `ToolResult`，统一工具返回。已完成第一版。
10. 引入 `ToolDefinition`、`ToolCall` 和 `ToolRegistry`，形成 MCP-style 工具层。已完成第一版。
11. 新增 `KnowledgeTools` 和 Markdown 知识库。已完成第一版。
12. 新增人工转接机制。已完成第一版。
13. 新增评测集和评测 Runner。已完成第一版。
14. 拆分 `CustomerServiceSkill` 中的槽位抽取、意图规则和 Prompt 构造。已完成第一版。
15. 将聊天接口请求体从 `Map` 改成明确的 `ChatApiRequest` DTO。已完成第一版。
16. 将空消息错误从临时 `Map` 改成明确的 `ApiErrorResponse`。已完成第一版。
17. 新增 `GlobalExceptionHandler`，统一 Controller 异常响应。已完成第一版。
18. 给 `ChatApiRequest` 增加 Spring Validation，并补充 MVC 层接口测试。已完成第一版。
19. 给评测报告增加 `history/` 历史快照和 `trend.json` 趋势入口。已完成第一版。
20. 将订单和商品工具的结构化输出从 Map 升级为明确 DTO。已完成第一版。
21. 将退货、用户和人工转接工具的结构化输出升级为明确 DTO。已完成第一版。
22. 增加 LLM 调用失败降级回复，并补充 Runtime Trace 测试。已完成第一版。
23. 为工具定义补充 outputSchema，并在前端 Tools 面板展示 input/output schema。已完成第一版。
24. 增强前端 Agent Inspector：Demo 脚本、Trace 过滤、Trace 指标摘要、Trace 复制、工具搜索、工具 Schema 复制和 Eval 评测报告看板。已完成第一版。
25. 新增 MCP Adapter：提供 `/api/mcp/tools` 和 `/api/mcp/tools/call`，并补充接口测试。已完成第一版。
26. 新增 Monitor 指标闭环：基于 Trace 聚合整体/按工具成功率、LLM 失败率、转人工次数和平均耗时，并在前端 Monitor 面板展示。已完成第一版。
27. 增强 RAG 检索：新增查询改写、多路召回、简单 rerank、`knowledge_miss` Trace 和 RAG Trace 过滤。已完成第一版。
28. 升级多轮记忆管理：新增短期记忆窗口、任务记忆快照、规则摘要压缩、Memory API、Memory Trace 和前端 Memory Tab。已完成第一版。
29. 新增 LLM-as-Judge 评测第一版：四维度 Judge Prompt、结构化 JSON 解析、Fake Judge 稳定测试和 `llm-as-judge.json` 报告。已完成第一版。
30. 新增 Docker 部署第一版：多阶段 Dockerfile、docker-compose、`.env.example`、环境变量配置和 `/api/health` 健康检查。已完成第一版。
31. 新增长期用户偏好记忆第一版：记录商品偏好、推荐时读取偏好、Memory 面板展示用户长期记忆。已完成第一版。
32. 新增记忆清除与隐私控制第一版：支持清空会话记忆和用户长期记忆。已完成第一版。
33. 新增用户反馈闭环第一版：支持回复有用/无用反馈、Monitor 聚合有用率和最近反馈。已完成第一版。
34. 新增反馈样本进入 Eval 候选池第一版：无用反馈自动生成待审核候选样本，并在 Monitor 面板展示。已完成第一版。
35. 新增 Eval 候选样本审核第一版：支持 `APPROVED` / `REJECTED` 状态、审核备注和前端快捷审核。已完成第一版。
36. 新增 Eval case 草稿导出第一版：支持把 `APPROVED` 候选样本导出为 response-quality 评测草稿 JSON。已完成第一版。
37. 新增负反馈问题类型分类第一版：支持给 Eval 候选样本打上 `issueType`，并在 Monitor Summary 和前端展示问题类型分布。已完成第一版。
38. 新增 Monitor 风险告警第一版：根据工具失败、LLM 失败、低有用反馈率、不安全承诺、内部实现泄露和候选样本积压生成结构化告警，并在前端 Monitor 面板展示。已完成第一版。
39. 新增 Eval 候选样本本地持久化第一版：默认保存到 `data/feedback-eval-candidates.json`，支持通过 `SMARTCS_MONITOR_EVAL_CANDIDATES_FILE` 覆盖路径，并在服务重启后恢复候选样本和 ID 序号。已完成第一版。
40. 新增 Eval 草稿半自动合并正式评测集第一版：开发者补齐断言后，可以把 `APPROVED` 草稿合并到 `response_quality_eval_cases.json`，并支持重复 ID 跳过和基础断言校验。已完成第一版。
41. 新增质量闭环演示脚本第一版：通过 `scripts/demo-quality-loop.ps1` 演示负反馈、候选样本、审核、合并临时评测文件和运行回复质量评测的完整链路。已完成第一版。
42. 新增真实模型 Judge 可选评测入口：默认 `mvn test` 使用 Fake Judge，手动运行 `scripts/run-real-judge-eval.ps1` 时调用真实模型生成 LLM-as-Judge 报告。已完成第一版。
43. 新增 CI 质量门禁第一版：通过 GitHub Actions 在 push / PR 时使用 Java 17 自动运行 `./mvnw -B test`，验证单元测试、接口测试和核心 Eval 链路。已完成第一版。
44. 新增数据库持久化第一版：Repository 层支持 `memory` / `jdbc` 双模式，开启 JDBC 后用户、商品、订单和退货记录可持久化到 MySQL。已完成第一版。
45. 新增独立 MCP Server 第一版：新增 `/mcp` JSON-RPC 风格入口，支持 `initialize`、`tools/list`、`tools/call`，复用现有 `ToolRegistry`。已完成第一版。
46. 新增 RAG 本地向量检索升级第一版：使用字符 n-gram 伪向量和余弦相似度补充召回分，并在结果中暴露 `vectorScore`。已完成第一版。
47. 新增 Multi-Agent 路由第一版：新增 `AgentRouteResult`，支持售前、售后、投诉、通用专家域路由，并写入 `agent_routed` Trace。已完成第一版。
48. 新增售后工单平台联调第一版：`HandoffTools` 支持 `memory` / `after-sales-http` 双模式，联调时调用桌面 `demo/after-sales-sla-platform` 创建真实售后工单。已完成第一版。
49. 新增真 embedding + Qdrant 向量数据库 RAG 第一版：`KnowledgeTools` 支持 `local` / `embedding-qdrant` 双模式，高级模式调用 embedding API 并写入 Qdrant，失败时回退本地 RAG。已完成第一版。

## 9. 暂缓事项

以下内容暂时不要急着做：

- MySQL 持久化
- 完整 MCP Server 高级能力，例如鉴权、独立进程部署和流式协议
- 多模型路由
- Monitor 动态调参
- 复杂 UI 重构

原因：

这些能力确实高级，但如果基础 Agent 链路、多轮状态、Trace 和评测没有做好，提前做这些会让项目变成“概念很多，但核心不稳”。其中 MySQL 持久化已经完成第一版，后续暂缓的是更完整的连接池、迁移工具、外键索引和会话/反馈数据迁移。

## 10. 当前结论

我们当前项目的方向是正确的。

下一步最重要的不是继续堆功能，而是进入“稳定、演示、面试准备”阶段：

- 固化面试讲解主线，确保每个亮点都能讲清楚“为什么做、怎么做、怎么验证”。
- 固化演示流程，优先演示多轮退货申请、RAG 政策问答、Trace、Monitor 和质量闭环。
- 保持测试稳定，后续每次改动都要确认核心评测和相关单元测试通过。
- 把二期能力放入候选池，不急着一次性做完，避免项目从“扎实”变成“堆概念”。

多轮任务状态、三级会话记忆第一版、长期用户偏好记忆第一版、记忆清除与隐私控制、Agent Trace、MCP-style 工具层、MCP Adapter、工具 inputSchema/outputSchema、业务工具 DTO、知识库 RAG、RAG 查询改写、多路召回、简单 rerank、本地轻量向量分、真 embedding + Qdrant 向量数据库 RAG、人工转接、LLM 失败降级、Monitor 在线指标、按工具名成功率统计、Monitor 风险告警、用户反馈闭环、反馈样本进入 Eval 候选池、Eval 候选样本问题类型分类、Eval 候选样本审核、Eval 候选样本本地 JSON 持久化、Eval case 草稿导出、Eval 草稿半自动合并正式评测集、质量闭环演示脚本、可选真实模型 Judge 评测、前端 Agent Inspector、Memory 面板、Demo 演示脚本、Eval 评测报告看板、离线路由评测、知识库召回评测、回复质量规则评测、Agent 链路回复评测、LLM-as-Judge 第一版、Docker 部署第一版、评测报告 JSON 化、评测历史趋势、结构化工具数据、Skill 第一轮拆分、接口 DTO、参数校验和统一错误响应已经完成。

当前新增了两份面试和演示准备文档：

- [客服Agent-25-面试亮点总结.md](客服Agent-25-面试亮点总结.md)
- [客服Agent-26-演示流程.md](客服Agent-26-演示流程.md)
- [客服Agent-27-CI质量门禁设计.md](客服Agent-27-CI质量门禁设计.md)
- [客服Agent-28-数据库持久化设计.md](客服Agent-28-数据库持久化设计.md)
- [客服Agent-29-独立MCPServer设计.md](客服Agent-29-独立MCPServer设计.md)
- [客服Agent-30-RAG向量检索升级设计.md](客服Agent-30-RAG向量检索升级设计.md)
- [客服Agent-31-Multi-Agent路由设计.md](客服Agent-31-Multi-Agent路由设计.md)
- [客服Agent-32-售后工单平台联调设计.md](客服Agent-32-售后工单平台联调设计.md)
- [客服Agent-33-Embedding向量数据库RAG设计.md](客服Agent-33-Embedding向量数据库RAG设计.md)

如果后续继续做二期功能，建议从下面候选项中选择，不建议同时铺开：

- 真实 Agent 回复候选样本采集：让 Judge 可以评估真实模型生成的客服回复。
- 独立 MCP Server 增强：当前已完成同进程 `/mcp` JSON-RPC 第一版，后续再拆成真正可独立部署的工具服务。
- 数据库持久化增强：当前已完成业务核心表第一版，后续再迁移会话记忆、用户偏好和反馈候选样本。
- CI 质量门禁增强：当前已完成 GitHub Actions 第一版，后续可以上传 Eval 报告 artifact 或在 PR 中展示指标摘要。
- RAG 向量检索增强：当前已完成本地轻量向量分和真 embedding + Qdrant 第一版，后续再做文档切块、增量索引和更强 rerank。

下一阶段不再零散堆功能，而是按模块推进。详细任务拆分见：

- [客服Agent-15-后续模块化开发计划.md](客服Agent-15-后续模块化开发计划.md)

优先级顺序为：

1. 面试亮点总结与演示流程固化。已完成第一版。
2. 工具协议与 MCP 边界增强。已完成本地 Adapter 第一版，二期待拆独立 MCP Server。
3. Monitor 指标与 Eval 闭环增强。已完成第一版，二期待接 CI artifact 或真实线上数据。
4. RAG 检索增强。已完成轻量召回第一版和真 embedding + Qdrant 第一版，二期待补文档切块、增量索引和更强 rerank。
5. 多轮记忆管理升级。已完成第一版。
6. LLM-as-Judge 评测。已完成第一版。
7. Docker 与部署工程化。已完成第一版。
8. CI 质量门禁。已完成第一版。
9. 数据库持久化。已完成第一版。
10. 独立 MCP Server。已完成同进程 JSON-RPC 第一版。
11. RAG 本地向量检索。已完成第一版。
12. Multi-Agent 路由。已完成第一版。
13. 售后工单平台联调。已完成第一版。
14. 真 embedding + Qdrant 向量数据库 RAG。已完成第一版。
