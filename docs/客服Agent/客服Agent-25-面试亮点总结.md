# 客服Agent-25-面试亮点总结

## 1. 项目一句话

Smart CS Agent 是一个面向电商客服场景的任务型 Agent 系统，基于 Spring Boot + LangChain4j 构建。它不是只做聊天回复，而是把用户意图识别、多轮任务状态、业务工具调用、知识库检索、人工转接、Trace 可观测、Monitor 指标、用户反馈和离线评测串成一条完整链路。

面试时可以这样开场：

> 我做的是一个电商客服 Agent，不只是调用大模型生成回复，而是把客服任务拆成 Skill Package、
> Workflow 决策、Runtime 执行、Capability 业务能力和 Monitor/Eval 质量闭环。系统能处理订单查询、
> 退货申请、商品咨询、政策问答和人工转接，并且能通过 Trace 和评测集验证每一步是否可靠。

## 2. 面试主线

建议按这条顺序讲，逻辑会比较清楚：

1. 为什么选择客服 Agent。
2. 整体架构如何拆分。
3. 多轮会话如何保存状态。
4. 工具调用如何标准化。
5. RAG 如何避免模型乱答政策。
6. Trace 和 Monitor 如何定位问题。
7. Eval 如何让项目可验证。
8. 用户反馈如何沉淀成回归测试。
9. 当前不足和后续优化。

不要一上来堆名词。先讲“问题”，再讲“我怎么拆”，最后讲“如何证明它有效”。

## 3. 架构亮点

### 3.1 Skill Package / Workflow / Runtime / Capability 分层

核心思想：

- `Skill Package` 负责指令、知识资料、版本和 Capability allowlist。
- `Workflow` 负责判断当前应该做什么，并保存任务状态。
- `Runtime` 负责执行 Workflow 规划出的通用 Action。
- `Capability Provider` 负责具体业务能力，比如查订单、查商品、退货、查知识库。
- `Trace` 负责记录执行过程。

可以这样讲：

> 我没有把所有逻辑都写在 Controller 或一个大 Service 里，而是把指令资料、任务状态机、通用执行循环
> 和原子业务能力拆开。新增业务 Workflow 或 Provider 时，不需要修改 AgentRuntime 的循环。

面试官可能追问：

**为什么不直接让 LLM 自己决定所有工具调用？**

回答思路：

> 客服场景里有很多高频且边界明确的任务，比如订单号、退货原因、人工转接，这些用规则更稳定、更可测。LLM 更适合处理语言表达和兜底回复。我的设计是规则优先，LLM 辅助，而不是把所有控制权都交给模型。

### 3.2 多轮会话与任务状态

项目把一次请求、跨轮状态和本轮执行上下文拆开：

- `ChatRequest`：当前这一轮用户输入。
- `ConversationState`：跨轮保存的会话状态和任务槽位。
- `TurnContext`：本轮执行过程中产生的观察结果、Trace 和最终回复。

可讲示例：

```text
用户：我要退货
Agent：请提供订单号
用户：ORD-004
Agent：请说明退货原因
用户：质量问题
Agent：已为你提交退货申请
```

这条链路体现了 Agent 不只是单轮问答，而是能记住“上一轮正在收集退货申请所需信息”。

### 3.3 Capability 与 MCP 层

当前能力层使用一套权威协议：

- `CapabilityDefinition`
- `CapabilityCall`
- `CapabilityResult`
- `inputSchema`
- `outputSchema`
- `CapabilityGateway` / `CapabilityProvider`
- `/api/mcp/tools`
- `/api/mcp/tools/call`
- `/mcp` 的 `initialize`、`tools/list`、`tools/call`

可以这样讲：

> 我把本地和远程能力统一为 Capability 契约，每个能力都有稳定 ID、输入/输出 schema 和三态结果。
> Workflow 只依赖 Gateway，不感知能力来自本地 Provider 还是 MCP。REST Catalog 与 MCP tools/list 共用
> 同一组定义，因此不会出现两套 schema 漂移。

注意不要说“已经完整实现 MCP 协议”。更稳妥的说法是：

> 当前实现了项目需要的 MCP JSON-RPC 子集和 HTTP Bearer 身份边界，但没有声称覆盖 MCP 规范的全部
> 能力，例如动态 Catalog 原子替换、签名用户委托和完整生命周期仍未实现。

### 3.4 RAG 知识库检索

项目用 Markdown 知识库承载客服政策，并做了：

- 查询改写。
- 多路召回。
- 简单 rerank。
- `knowledge_miss` Trace。
- 知识库召回评测。

可讲取舍：

> 我没有一开始就引入向量数据库，因为项目当前知识量不大，而且我希望先把召回链路、Trace 和评测做清楚。Markdown 检索虽然简单，但可解释、可调试，适合项目早期验证 RAG 流程。

### 3.5 Trace 可观测

Trace 能看到：

- 会话开始。
- 命中的 Skill。
- 规划出的 Action。
- 工具调用参数。
- 工具结果。
- LLM 调用和失败。
- 最终回复。
- 耗时信息。

面试表达：

> Agent 最大的问题之一是黑盒。我给每轮对话都记录 Trace，前端也能查看。这样当用户说回答不对时，可以定位是意图错了、槽位没抽到、工具失败、知识库没召回，还是 LLM 回复质量不好。

### 3.6 Monitor 与用户反馈闭环

当前 Monitor 能聚合：

- 工具成功率。
- LLM 失败率。
- 转人工次数。
- 平均耗时。
- 用户反馈有用率。
- 负反馈问题类型。
- 结构化风险告警。

反馈闭环链路：

```text
用户点击无用
-> 后端记录 Feedback
-> 生成 Eval 候选样本
-> 人工审核 APPROVED / REJECTED
-> 导出 Eval case 草稿
-> 人工补齐断言
-> 合并进正式评测集
-> 后续回归测试覆盖
```

这部分是项目最值得讲的亮点之一，因为它说明你不仅会“做功能”，也在思考“如何持续改进 Agent 质量”。

### 3.7 离线评测体系

项目现在有五类评测：

- 意图和路由评测。
- 知识库召回评测。
- 回复质量规则评测。
- Agent 链路回复评测。
- LLM-as-Judge 评测。

面试表达：

> 我不只看 Demo 是否能跑，还给不同模块设计了评测集。规则评测保证稳定性，LLM-as-Judge 用来补充主观回复质量判断。默认测试用 Fake Judge，避免 CI 或本地测试依赖外部模型；需要时可以手动运行真实 Judge 脚本。

## 4. 简历推荐写法

### 4.1 简历一句话

智能客服 Agent：基于 Spring Boot + LangChain4j 实现电商客服 Agent，采用 Skill Package / Workflow /
Runtime / Capability 分层架构，支持多轮任务状态、MCP/JSON-RPC 能力调用、RAG 知识库、Agent Trace、
Monitor 反馈闭环、LLM-as-Judge 与离线评测，提升客服场景下大模型应用的可控性、可观测性和可验证性。

### 4.2 简历项目描述

可以写成 3 到 5 条：

- 设计 Skill Package / Workflow / Runtime / Capability 分层架构，将指令与授权、任务状态机、通用执行
  循环和原子业务能力解耦，支持订单查询、商品咨询、退货申请、政策问答和人工转接等客服场景。
- 实现多轮会话状态管理，使用 `ConversationState` 保存任务槽位、短期消息窗口和摘要记忆，支持退货申请等跨轮槽位填充流程。
- 设计统一 Capability 契约，以 `CapabilityDefinition`、`CapabilityCall`、`CapabilityResult` 和
  输入/输出 schema 作为唯一协议；REST 与 MCP 共用 Catalog，本地和远程 Provider 可配置替换。
- 构建 Agent Trace、Monitor 指标和用户反馈闭环，将无用反馈沉淀为 Eval 候选样本，支持审核、导出、半自动合并正式评测集和回归测试。
- 建设五类离线评测，包括路由评测、知识库召回评测、回复质量规则评测、Agent 链路评测和 LLM-as-Judge 评测，并输出 JSON 报告、历史快照和趋势指标。

## 5. STAR 讲法

### S：背景

电商客服问题高频、重复，但又涉及订单、商品、售后政策和人工转接等多个业务系统。单纯把用户问题发给大模型容易出现乱答、漏问信息、工具调用不可控和质量难评估的问题。

### T：目标

构建一个可解释、可扩展、可评测的客服 Agent，让它能够完成真实客服任务，而不是只生成聊天文本。

### A：行动

我把系统拆成 Skill 决策层、Runtime 执行层和 Tool 工具层；为工具设计统一 schema 和返回结构；用 `ConversationState` 管理多轮任务状态；用 Markdown 知识库做 RAG 政策问答；用 Trace 记录每轮执行链路；最后补充 Monitor、用户反馈和离线 Eval，让系统质量可以持续验证。

### R：结果

项目支持订单查询、商品咨询、退货申请、政策问答、人工转接等核心客服场景；前端可以查看 Trace、
Memory、Tools、Monitor 和 Eval 报告；测试覆盖路由、RAG、回复质量、Agent 链路和 LLM-as-Judge，
Phase 9.5 的 499 个默认测试全部通过，并完成真实 MySQL、JAR 和 Docker/Compose 验收。

## 6. 高频面试问答

### Q1：这个项目和普通 ChatBot 有什么区别？

普通 ChatBot 更关注生成回复，这个项目关注任务执行。它会识别意图、追问缺失信息、调用业务工具、检索知识库、记录 Trace，并通过 Eval 验证链路质量。

### Q2：为什么要拆 Skill Package、Workflow、Runtime、Capability？

因为四者变化原因不同：Skill Package 随指令和资料变化，Workflow 随业务状态机变化，Runtime 随通用
执行机制变化，Capability 随领域系统或传输方式变化。拆开后可以独立测试，也避免把 Agent Skill 和
可执行 Java Workflow 混成一个概念。

### Q3：为什么只实现 MCP 的项目所需子集？

项目已实现 `/mcp` 的 `initialize`、`tools/list`、`tools/call` 和 Bearer 身份边界，并让 REST/MCP 共用
Capability Catalog。当前没有业务需求支撑完整协议生命周期、动态 Catalog 原子替换和签名用户委托；
先把实际需要的协议边界做对，比声称“完整支持”更准确。

### Q4：RAG 为什么没有直接用向量数据库？

默认 local 模式使用 Markdown + 查询改写 + 多路召回 + rerank，保证小知识库场景可解释、可离线评测；
项目也已提供可选 embedding + Qdrant 模式，并在外部服务失败时回退本地检索。生产选择取决于知识规模、
召回质量、成本和外部依赖 SLA，而不是为了使用向量库而使用。

### Q5：如何判断 Agent 回复质量？

项目用了多层评测。规则评测检查关键词、禁用词和结构化要求；Agent 链路评测检查完整执行结果；LLM-as-Judge 从事实一致性、帮助性、工具使用合理性和客服语气等维度评分。

### Q6：如果工具调用失败怎么办？

能力统一返回 `CapabilityResult`，用 `SUCCESS`、`BUSINESS_REJECTED`、`TECHNICAL_FAILURE` 区分成功、
业务拒绝和技术失败，并携带稳定 `errorCode`。副作用超时等“结果未知”会使用专用错误码和 Outbox 状态
继续保留语义。Runtime 会把 Observation 记录到 Trace；LLM 失败时也有结构化降级回复，避免接口直接
崩溃或把未知副作用误报成普通失败。

### Q7：用户反馈闭环有什么价值？

它把线上质量信号变成可回归测试资产。用户点“无用”后，系统生成候选样本，人工审核和补齐断言后可以合并进正式评测集。下次改代码或改 Prompt 时，这类问题就能被测试覆盖。

### Q8：项目目前最大的不足是什么？

可以坦诚说：

- 当前数据层是模拟仓储，还没有接真实数据库。
- 当前是 MCP-style Adapter，还不是独立 MCP Server。
- RAG 仍是轻量级本地检索，尚未接向量数据库和真实 rerank 模型。
- Multi-Agent 拆分还没有作为核心功能落地。
- Monitor 目前偏离线和本地演示，还没有接入真实生产监控系统。

然后补一句：

> 我没有刻意把所有高级概念一次性堆满，而是先把客服 Agent 的核心闭环做扎实，再逐步替换底层实现。

## 7. 面试演示建议

优先演示 3 条链路：

1. 多轮退货申请：体现任务状态和槽位填充。
2. 政策问答：体现 RAG、知识库召回和 Trace。
3. 质量闭环脚本：体现反馈如何进入 Eval。

不要现场演示太多功能。面试时间有限，演示越聚焦，项目越显得成熟。

## 8. 30 秒版本

> 这个项目是一个电商客服 Agent。我把它拆成 Skill Package、Workflow、Runtime 和 Capability 四层，
> 支持订单查询、商品咨询、退货申请、政策问答和人工转接。为了让 Agent 不黑盒，我做了 Trace、Monitor
> 和前端调试面板；为了让质量可验证，我做了路由、RAG、回复质量、Agent 链路和 LLM-as-Judge 五类
> 评测；用户负反馈还能经过审核后沉淀进回归测试集。

## 9. 2 分钟版本

> 我做的是一个基于 Spring Boot + LangChain4j 的电商客服 Agent。它不是简单调用大模型聊天，而是一个任务执行系统。用户进来后，Agent 会先判断意图，如果信息不完整就追问，比如退货申请会先问订单号，再问退货原因；信息齐全后通过统一工具层调用订单、商品、退货、用户或知识库工具。
>
> 架构上我拆成 Skill Package、Workflow、Runtime 和 Capability。Skill Package 保存指令、资料与能力
> allowlist，Workflow 负责状态和下一步决策，Runtime 只执行通用 Action 循环，Capability Provider 提供
> 原子业务能力。本地与 MCP Provider 共用 inputSchema、outputSchema 和三态 CapabilityResult，所以
> Workflow、前端、测试和协议 Adapter 使用同一套边界。
>
> 为了避免 Agent 黑盒，我给每轮对话记录 Trace，能看到意图、Action、工具参数、工具结果、LLM 调用和耗时。为了验证质量，我做了五类离线评测，包括路由评测、知识库召回评测、回复质量规则评测、Agent 链路评测和 LLM-as-Judge。最后还做了 Monitor 和用户反馈闭环，用户点击无用后会生成 Eval 候选样本，审核和补齐断言后可以合并进正式评测集，形成回归测试。
