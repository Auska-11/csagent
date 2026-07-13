# 客服 Agent 项目简历与面试完整手册

> 整理日期：2026-07-13
> 代码基线：当前工作区，包含 Phase 8 第十四批 PendingHandoffStore 持久化基础
> 验证结果：mvn test 共 383 项，0 失败、0 错误、0 跳过
> 使用原则：只描述代码已经证明的能力；规划中的能力必须明确说“尚未完成”

## 1. 这份文档怎么用

这份文档同时服务三个目的：

1. 从项目全貌理解当前到底实现了什么。
2. 提取可以直接放进简历的项目描述和技术亮点。
3. 准备面试中的项目介绍、架构追问、故障场景和不足回答。

建议按下面顺序使用：

- 写简历时先看第 3 节，选择 4 至 6 条最能讲清楚的亮点。
- 准备自我介绍时背熟第 12 节的 30 秒和 2 分钟版本。
- 准备技术面试时重点理解第 4、5、7、8、9 节。
- 面试前一天使用第 13 节逐题口述，不要只看文字。
- 演示项目时按第 14 节执行，避免现场临时发挥。

## 2. 项目当前事实基线

### 2.1 一句话定位

Smart CS Agent 是一个面向电商客服场景的 Java 智能 Agent 系统。它不是只把用户消息转发给大模型，而是把客服任务拆成可路由、可恢复、可审计、可测试的 Workflow，并通过统一 Capability 契约调用本地业务能力或远程 MCP 服务。

### 2.2 技术栈

| 分类 | 当前使用 |
| --- | --- |
| 语言与框架 | Java 17、Spring Boot 3.3 |
| LLM 集成 | LangChain4j 0.35、OpenAI-compatible Chat API |
| Web 与校验 | Spring MVC、Bean Validation、统一异常响应 |
| 安全 | Spring Security、OAuth2 Resource Server、JWT、RBAC |
| 数据 | 内存 Repository、MySQL JDBC 双模式 |
| Agent 架构 | Runtime、Workflow、Action Handler、Capability Gateway |
| MCP | JSON-RPC MCP Server Adapter、HTTP MCP Client、Local/MCP Provider |
| RAG | Markdown 知识库、关键词召回、本地 n-gram 向量、可选 Embedding + Qdrant |
| 可观测性 | Trace、Monitor、TurnOutcome、Liveness、Readiness |
| 质量 | JUnit 5、Spring Boot Test、MockMvc、离线 Eval、GitHub Actions |
| 部署 | Maven、Docker 多阶段构建、Docker Compose |

### 2.3 当前可量化规模

| 指标 | 当前值 | 应怎样理解 |
| --- | ---: | --- |
| 默认自动化测试 | 383 项 | 当前工作区全量 mvn test 实测通过 |
| 测试源码文件 | 103 个 | 包含单元、集成、架构边界和 Eval 测试 |
| Java 主代码文件 | 271 个 | 反映项目已经超过单文件 Demo 规模 |
| 离线评测样本 | 36 条 | 路由 16、召回 8、规则回复 6、链路回复 3、Judge 3 |
| Agent Skill Package | 9 个 | 运行时从 manifest、SKILL.md 和 references 加载 |
| 独立业务 Workflow | 8 个 | 另保留一个 Legacy 回滚适配器 |
| 稳定 Capability ID | 9 个 | Local 与 MCP 共用同一命名和契约 |
| Turn 最终结果类型 | 5 类 | 成功、业务拒绝、技术失败、安全拒绝、用户取消 |
| 持久化表 | 6 张 | 4 张业务表、Session 表、Capability 幂等表 |

### 2.4 当前业务范围

已经实现的主要客服场景：

- 查询单个订单状态和物流。
- 查询当前认证用户的全部订单。
- 商品搜索、价格、库存和目录内推荐。
- 多轮退货申请：收集订单号、原因、确认后提交。
- 查询退货或退款进度。
- 退货、发票、配送、保修等政策问答。
- 查询当前用户资料。
- 记录、查询和清除长期商品偏好。
- 用户主动转人工、投诉、赔偿和维权升级。
- 普通闲聊和无法识别场景的 LLM 兜底。

## 3. 简历可直接使用的版本

### 3.1 推荐项目名称

**Smart CS Agent：可扩展电商智能客服系统**

如果简历空间很紧，可以写：

**电商智能客服 Agent**

### 3.2 推荐项目描述

基于 Java 17、Spring Boot 和 LangChain4j 开发电商智能客服 Agent，将订单、商品、退货、政策问答和人工升级等场景拆分为独立 Workflow；通过 Action Handler 与 Capability Gateway 隔离决策和执行，并支持 Local/MCP Provider、JWT/RBAC、多轮会话持久化、幂等防重、RAG、Trace/Monitor 和离线评测。

### 3.3 推荐职责与亮点

下面 6 条适合放在一页简历中。不要全部继续扩写，否则项目会挤占其他经历。

1. 设计 Runtime、Workflow、Action Handler、Capability Gateway 四层架构，将业务决策、动作执行和基础能力解耦；通过 Spring 自动注册实现扩展新 Workflow 时不修改 Runtime 和 Router 核心分支。
2. 将订单、商品、退货申请、退货查询、政策问答、用户资料、人工升级和保修查询拆成 8 个独立 Workflow，并用 9 个 Agent Skill Package 管理 Prompt、references、版本和能力白名单。
3. 建立统一 Capability 契约和 Local/MCP Provider 适配层，9 个稳定能力 ID 共用输入输出 Schema、三态结果、身份要求、风险等级和幂等策略，使 Workflow 不感知能力位于本地还是远程。
4. 实现多轮会话状态、SessionStore 内存/JDBC 双实现、版本 CAS、Workflow 状态迁移链和副作用幂等租约；覆盖会话重建恢复、并发冲突、结果回放及 Session 保存失败后的防重复调用。
5. 接入 Spring Security JWT Resource Server 和角色权限矩阵，使用 tenantId、userId、conversationId 复合所有权边界，并对公开 Trace 做递归脱敏、限长和不可变快照。
6. 建立 Trace、Monitor、用户反馈到 Eval 候选样本的质量闭环，维护 36 条离线评测样本和 383 项自动化测试，接入 GitHub Actions 作为持续回归门禁。

### 3.4 四条精简版

如果简历空间只能放四条，建议使用：

- 设计可插拔 Agent 架构，按 Runtime、Workflow、Action Handler、Capability 分层，完成 8 个业务 Workflow 和 9 个 Skill Package 的运行时接入。
- 统一 Local/MCP 能力契约，支持 JSON-RPC tools/list、tools/call、远程发现、有限重试和基础熔断，并通过 Capability allowlist 控制最小权限。
- 实现会话 JDBC 持久化、版本 CAS、Workflow 状态迁移和副作用幂等租约，验证重建恢复、并发冲突与故障重试不重复调用 Provider。
- 建立 JWT/RBAC、Trace 脱敏、Monitor 五类 Turn 结果和离线 Eval 体系，当前 383 项测试与 36 条评测样本通过既定断言。

### 3.5 简历关键词

Java 17、Spring Boot、LangChain4j、Agent、Workflow、MCP、RAG、Qdrant、JWT、RBAC、JDBC、CAS、幂等、租约、状态机、可观测性、故障注入、离线评测、GitHub Actions。

### 3.6 不建议写进简历的夸张表述

不要写：

- 实现生产级分布式 exactly-once。
- 实现完整多 Agent 协作平台。
- 支持任意 Skill 热插拔和在线动态卸载。
- 完成真实 MySQL 高并发压测。
- 构建企业级安全身份平台。
- RAG 准确率达到 100%。
- 系统已经达到生产可用。

更准确的写法是：

- 建立副作用幂等、租约和结果回放基线，但仍保留崩溃窗口。
- 实现专家域路由 Trace 和多个独立 Workflow，不是分布式 Multi-Agent。
- 支持启动期自动发现和注册扩展，尚未实现运行时热卸载。
- 已通过 JDBC SQL 契约替身测试，真实 MySQL/Testcontainers 验收仍待补充。
- 建立 JWT/RBAC 基线，生产仍需独立 IdP、非对称密钥和撤销机制。
- 当前小规模离线评测集通过，不代表开放流量准确率。

## 4. 当前架构

### 4.1 总体调用链

~~~mermaid
flowchart TD
    A["Browser / API Client"] --> B["Spring Security Resource Server"]
    B --> C["ChatApiController"]
    C --> D["CustomerServiceAgent"]
    D --> E["ConversationStore"]
    E --> F["SessionStore: Memory / JDBC"]
    D --> G["AgentRuntime"]
    G --> H["WorkflowRouter"]
    H --> I["Independent AgentWorkflow"]
    I --> J["AgentAction"]
    J --> K["ActionHandlerRegistry"]
    K --> L["CapabilityActionHandler"]
    L --> M["CapabilityGateway"]
    M --> N["Local Provider"]
    M --> O["MCP Provider"]
    N --> P["Domain Service / Repository"]
    O --> Q["HTTP JSON-RPC MCP Server"]
    G --> R["TraceEvent"]
    R --> S["Monitor / Eval"]
    T["Agent Skill Package Registry"] --> H
    T --> L
~~~

### 4.2 每一层负责什么

| 层 | 主要职责 | 明确不负责 |
| --- | --- | --- |
| Controller | HTTP DTO、校验、身份转换、响应 | 不决定业务流程 |
| CustomerServiceAgent | 组装一轮会话入口 | 不写具体订单或退货规则 |
| ConversationStore | 加载、锁定、保存会话 | 不理解具体 Workflow 业务 |
| AgentRuntime | 通用观察-决策-执行循环和终止控制 | 不根据 Action 类型写业务分支 |
| WorkflowRouter | 恢复活跃任务或为新任务评分 | 不直接调用工具 |
| AgentWorkflow | 维护业务状态机并决定下一个 Action | 不直接访问 Repository |
| Action Handler | 执行追问、确认、LLM、Capability、转人工 | 不决定整个业务状态机 |
| Capability Gateway | 鉴权、deadline 前置检查、幂等、Provider 分发 | 不理解订单和退货业务 |
| Capability Provider | 把稳定能力契约适配到本地或远程实现 | 不控制多轮对话 |
| Skill Package | Prompt、说明、references、版本、能力白名单 | 不承担 Java 状态机执行 |
| Trace/Monitor | 记录事实并聚合质量指标 | 不反向控制 Runtime |

### 4.3 项目里的 Skill 到底是什么

项目中曾经存在两种容易混淆的 Skill：

1. 早期 Java Skill
   CustomerServiceSkill 同时包含意图判断、槽位逻辑和 Action 决策，职责较重。它现在主要作为 Legacy 回滚路径保留。

2. 当前 Agent Skill Package
   根目录 skills 下的 manifest.yaml、SKILL.md、Prompt 和 references 组成声明式能力包。它描述“这个业务是什么、允许调用什么能力、需要加载什么参考资料”，但真正可执行的状态机由 AgentWorkflow 实现。

因此当前更准确的关系是：

~~~text
Agent Skill Package = 说明书 + Prompt + references + 权限清单
AgentWorkflow        = 可执行状态机
Capability           = 原子业务能力
MCP                  = 能力的一种远程传输与暴露方式
AgentRuntime         = 通用执行内核
~~~

这也回答了“项目 Skill 是否和一般 Agent Skill 混淆”的问题：早期 Java Skill 的确混合了决策和执行概念；改造后的 Skill Package 已经更接近通用 Agent Skill，但项目仍用 Java Workflow 承担确定性业务编排。

### 4.4 为什么不把所有逻辑继续放在 AgentRuntime

如果 Runtime 同时判断订单、退货、投诉、工具调用和模型失败，每新增一个业务都会修改同一个核心类，最终形成大量 if、switch 和 instanceof。

当前设计让扩展点分别落在：

- 新业务流程：新增 AgentWorkflow。
- 新动作：新增 AgentAction 和对应 ActionHandler。
- 新原子能力：新增 CapabilityProvider。
- 新远程实现：新增或替换 MCP Provider。
- 新 Prompt 和资料：新增 Agent Skill Package。

Runtime 只看统一接口和 ActionFlow，因此核心循环不会随着客服业务数量线性膨胀。

## 5. 一轮请求是怎样执行的

以用户发送“我要退货”为例：

1. Spring Security 验证 Bearer JWT 的签名、issuer、audience、过期时间、subject 和 tenant。
2. JwtPrincipalConverter 将 JWT 转为 AuthenticatedPrincipal。
3. ChatApiController 只使用已认证 Principal 构造 ChatRequest，不信任 body 中自报的 userId。
4. CustomerServiceAgent 使用 tenantId、userId、conversationId 构造会话复合键。
5. ConversationStore 对同一复合键加本地锁，从 SessionStore 加载快照和版本。
6. AgentRuntime 记录 turn_started，并调用 WorkflowRouter。
7. Router 若发现存在 WAITING 状态的 Workflow，优先恢复；否则对已注册 Workflow 评分。
8. ReturnApplyWorkflow 根据当前私有状态决定追问订单号、追问原因或请求确认。
9. Runtime 将 WorkflowDecision 中的 Action 交给 ActionHandlerRegistry。
10. CapabilityActionHandler 检查 Skill allowlist，再构造 ExecutionContext。
11. CapabilityGateway 检查身份、认证、deadline 是否已过期和幂等键，然后分发到 Provider。
12. Provider 调用领域服务和 Repository，返回 SUCCESS、BUSINESS_REJECTED 或 TECHNICAL_FAILURE。
13. Workflow 根据 CapabilityObservation 决定继续、追问、完成、取消或失败。
14. Runtime 写入终态 Trace 和助手回复。
15. ConversationStore 使用读取时的 expectedVersion 做 CAS 保存。
16. Controller 将 ChatResponse 交给 Monitor，分类本轮最终结果并返回前端。

这条链路的核心特点是：当前输入、跨轮状态、本轮观察结果、能力执行和公开 Trace 各自有明确对象，不再全部塞进一个 Map 或一个 Agent 类。

## 6. 已经实现的功能全景

### 6.1 独立业务 Workflow

| Workflow | 主要功能 | 关键工程点 |
| --- | --- | --- |
| order_query | 单订单和用户订单列表查询 | 身份归属、缺少订单号时追问 |
| return_apply | 多轮退货申请 | 槽位、确认、取消、副作用幂等 |
| return_query | 退货进度查询 | 用户归属校验 |
| product_advisory | 商品搜索、价格、库存、推荐 | 长期偏好参与推荐 |
| policy_qa | 退货、发票、配送政策 | RAG 检索后回答 |
| user_profile | 用户资料和长期偏好 | 认证用户边界 |
| escalation | 人工、投诉、赔偿、维权 | 高优先级全局升级 |
| warranty_query | 保修政策查询 | 用于证明新业务不修改核心 |

### 6.2 Agent Skill Package

当前运行时加载 9 个 Package：

- customer-service
- order-query
- return-apply
- return-query
- product-advisory
- policy-qa
- user-profile
- escalation
- warranty-query

每个 Package 可以声明：

- id、version、description、priority。
- 绑定的 workflow。
- triggers。
- allowed_capabilities。
- risk_level。
- confirmation_policy。
- SKILL.md。
- 按用途或触发词加载的 references。

Registry 会在启动时检查重复 ID、Workflow 绑定、文件路径和权限声明。新 Package 不需要在 Runtime 中注册。

### 6.3 Action Handler 插件化

当前动作类型包括：

- AskUserAction：向用户追问。
- ConfirmAction：副作用前请求确认。
- CapabilityAction：调用原子业务能力。
- LLMAction：生成自然语言回复。
- FinalAnswerAction：直接结束本轮。
- HandoffAction：转人工。
- CancelWorkflowAction：结构化取消。

Spring 会自动收集 ActionHandler Bean。重复处理器在启动时失败，未知 Action 会产生安全技术失败，不会静默空转。

### 6.4 Capability 与 MCP

当前 9 个稳定 Capability ID：

- order.lookup
- order.list-by-user
- product.search
- product.lookup
- return.apply
- return.lookup
- user.lookup
- knowledge.search
- handoff.request

CapabilityDefinition 统一声明：

- 版本。
- 描述。
- 输入和输出 Schema。
- 是否需要身份。
- 是否需要认证。
- 是否有副作用。
- 是否要求幂等。
- 默认超时元数据。
- 风险等级。

MCP Server 支持：

- initialize
- tools/list
- tools/call

MCP Client 已实现：

- Bearer JWT 服务身份。
- JSON-RPC 2.0 envelope 校验。
- 响应 id 和 result/error 互斥校验。
- initialize、tools/list、tools/call 结果结构校验。
- 网络错误、HTTP 5xx、408、429 有限重试。
- 401、403 和协议错误不重试。
- 连续失败熔断和冷却后恢复。
- 工具发现 TTL 缓存。

### 6.5 多轮会话与状态恢复

ConversationStore 使用以下复合键：

~~~text
(tenantId, userId, conversationId)
~~~

它解决两个问题：

- 不同用户使用相同 conversationId 时不会串会话。
- 会话锁、状态、Trace 和幂等作用域使用一致 owner 边界。

SessionStore 有两种实现：

- InMemorySessionStore：默认测试和本地 Demo。
- JdbcSessionStore：MySQL 持久化。

会话快照只保存跨轮需要的数据，不保存本轮 Observation、ToolResult 等临时对象。每次写入携带 expectedVersion，旧版本更新会得到 CAS 冲突。

### 6.6 Workflow 状态演进

每个 Workflow 的私有状态都有独立 schemaVersion。WorkflowStateMigrationRegistry 支持注册逐步迁移：

~~~text
v1 -> v2 -> v3
~~~

系统会拒绝：

- 重复迁移器。
- 跳版本迁移。
- 迁移链缺口。
- 数据库版本高于当前代码。
- 迁移中途失败后的半成品。

当前生产 Workflow 仍主要使用 v1，迁移基础设施通过合成多版本链验证。

### 6.7 副作用幂等

CapabilityIdempotencyStore 有内存和 JDBC 两种实现。

JDBC 实现使用：

- storageKey 的 SHA-256 唯一主键。
- 参数指纹。
- IN_PROGRESS 和 COMPLETED 状态。
- owner token。
- 短租约和后台续租。
- 完成结果 JSON 回放。

同一幂等键的并发请求只允许一个执行者进入 Provider，其他请求等待或回放结果。不同参数复用同一 key 会被拒绝。幂等存储失败时 Gateway 失败关闭，不会绕过 Store 直接执行副作用。

已经验证一个关键故障场景：

~~~text
退货 Provider 已成功
-> Session 保存失败
-> 用户重试
-> 使用同一幂等键回放结果
-> Provider 总调用次数仍为 1
-> 只补写 Session
~~~

### 6.8 RAG 知识库

默认本地模式不依赖外部向量数据库：

- 从 classpath 加载 Markdown。
- 进行查询改写和领域词扩展。
- 标题、人工关键词、正文分别加权。
- 使用中文字符 1-gram、2-gram 生成轻量向量。
- 使用余弦相似度作为补充召回信号。
- 根据字段覆盖进行简单 rerank。
- 低于阈值时返回 knowledge_miss 语义。

可选 embedding-qdrant 模式：

- 调用 OpenAI-compatible embeddings API。
- 首次使用时创建 Qdrant collection。
- 写入知识文档向量。
- 使用 query embedding 做余弦检索。
- Embedding 或 Qdrant 异常时回退本地 RAG。

### 6.9 短期记忆与长期偏好

短期记忆包括：

- 最近消息窗口。
- 任务状态。
- 规则摘要。
- 摘要版本和压缩 Trace。

长期偏好包括：

- 默认 30 天 TTL。
- 重复偏好刷新有效期。
- 用户总量上限。
- 每用户偏好数上限。
- 单条长度上限。
- 用户级和偏好级淘汰。
- 手机号、邮箱、证件、长数字和常见凭据脱敏。
- 敏感信息-only 输入不保存。
- 查询和清空接口。

### 6.10 人工转接

支持两种模式：

- memory：本地生成模拟工单。
- after-sales-http：调用售后工单平台 POST /api/tickets。

工单请求包含 tenant、服务身份、Idempotency-Key、问题类型、优先级和摘要。投诉、赔偿和维权会路由到高优先级 EscalationWorkflow。

### 6.11 JWT 与 RBAC

HTTP 身份统一来自经过验证的 JWT，而不是请求体或 LLM 参数。

验证内容包括：

- HS256 签名。
- issuer。
- audience。
- expiration。
- subject。
- tenant_id。
- 允许的时钟偏差。

角色包括：

- CUSTOMER：聊天、会话 Memory、长期 Memory、提交反馈。
- ADMIN：Monitor、Eval、Tool、调试接口。
- MCP_CLIENT：MCP REST 和 JSON-RPC 接口。

未知地址默认拒绝。MCP_CLIENT 只表示服务身份，不能把 _meta 中的 userId 提升成客户身份；未实现签名用户委托时，身份型 Capability 会失败关闭。

### 6.12 Trace、Monitor 与 Eval

Trace 覆盖：

- turn_started。
- agent_routed。
- workflow_routed。
- skill_selected。
- action_planned。
- action_handled。
- capability/tool 调用。
- llm_started、llm_completed、llm_failed。
- workflow_waiting、workflow_completed。
- handoff。
- memory_loaded、memory_compressed。
- turn_timeout。

TraceEvent 是统一安全边界：

- 敏感 key 遮蔽。
- 用户原话、Prompt、回复、错误原文等自由文本默认省略。
- 递归处理 Map、集合和数组。
- 限制字符串、集合、Map 和递归深度。
- 防循环引用。
- 保存不可变深拷贝。

Monitor 把每个 Turn 分成五个互斥结果：

- SUCCESS
- BUSINESS_REJECTED
- TECHNICAL_FAILURE
- GUARDRAIL_REJECTED
- USER_CANCELLED

Monitor 还统计：

- 工具调用和成功率。
- LLM 调用、失败率和耗时。
- 人工转接次数。
- 用户反馈有用率。
- Eval 候选状态和问题类型。
- 结构化风险告警。

用户负反馈不会直接污染正式评测集，而是先生成 PENDING_REVIEW 候选，经人工审核、补充断言后再合并。

### 6.13 评测与 CI

当前 36 条评测样本：

| 类型 | 样本数 | 当前既定断言结果 |
| --- | ---: | --- |
| 路由 | 16 | 意图、Action、工具和转人工命中断言通过 |
| 知识召回 | 8 | 文档召回与关键词覆盖断言通过 |
| 回复质量规则 | 6 | 正例通过、危险负例拦截 |
| Agent 链路回复 | 3 | 回复断言和 Trace 覆盖通过 |
| LLM-as-Judge | 3 | 使用 Fake Judge 验证结构化链路 |

重要说明：

- 这些结果只代表当前小规模离线样本，不代表线上准确率。
- 默认 Judge 是 Fake Judge，用于稳定验证 Prompt、JSON 解析和报告链。
- 真实模型 Judge 是可选脚本，不属于默认 CI 的确定性结果。

GitHub Actions 会在 push 和 Pull Request 时使用 JDK 17 执行 Maven 测试。

## 7. 最值得在面试中讲的设计

### 7.1 决策和执行分离

Workflow 只决定“下一步做什么”，Handler 和 Provider 才负责“怎么执行”。这样可以单独测试状态机，也可以在不修改 Workflow 的情况下替换能力实现。

### 7.2 规则优先，LLM 兜底

订单号、退货步骤、身份校验和确认属于确定性逻辑，使用规则和状态机更稳定；自然语言润色和开放闲聊再交给 LLM。这样既减少幻觉，也降低测试不稳定性。

### 7.3 Local 与 MCP 共用契约

Workflow 只调用稳定 Capability ID，不知道背后是本地 Provider 还是远程 MCP。Catalog、调试接口、MCP tools/list 和契约测试尽量使用同一份 CapabilityDefinition，减少 Schema 漂移。

### 7.4 状态 CAS 与副作用幂等是两个问题

Session CAS 只能防止旧状态覆盖新状态，不能阻止两个实例都已经调用退货 Provider。副作用防重必须在 Provider 之前建立幂等 claim，并保存结果供重试回放。

### 7.5 可观测性不记录原始隐私

Trace 的价值是排查“走了什么链路”，不是保存完整用户原话。系统保留稳定 ID、枚举、状态、错误码和耗时，省略 Prompt、回复和敏感参数，在可观测性和隐私之间取平衡。

### 7.6 失败是结构化生命周期

系统不再根据回复文案猜成功或失败。ActionFlow、WorkflowStatus、CapabilityOutcome 和 TurnOutcome 分别表达执行流、任务状态、能力结果和整轮结果，避免“异常但标记 COMPLETED”的假成功。

## 8. 并发、持久化与故障恢复怎样讲

### 8.1 单实例会话并发

ConversationStore 为完整 ConversationKey 维护 ReentrantLock，把加载、Workflow 执行和保存放在同一临界区。同一会话串行，不同用户或会话仍可并行。

### 8.2 多实例状态冲突

多个实例拥有不同本地锁，因此最终依赖 SessionStore version CAS。两个实例同时读取 version 1 时，只允许一个更新到 version 2，另一个得到 SESSION_VERSION_CONFLICT。

### 8.3 多实例副作用

JDBC CapabilityIdempotencyStore 通过唯一 key、owner token 和 lease 竞争执行权。慢 Provider 执行期间后台续租，避免其他实例误接管。

### 8.4 为什么仍不能说 exactly-once

Provider 已经成功，但应用在保存 COMPLETED 幂等结果前崩溃时，租约过期后仍可能再次执行。真正关闭窗口通常需要：

- 下游系统识别相同 idempotencyKey。
- 领域表增加唯一业务约束。
- 同库事务或 Outbox。
- 对“超时但结果未知”建立查询与人工核对流程。

项目当前实现的是工程上有价值的 at-most-one-active-executor 和结果回放基线，不是数学意义上的 exactly-once。

## 9. 当前不足与风险

这一节面试时不要回避。能清楚说明边界，通常比声称“全部生产级”更可信。

### 9.1 高优先级

1. Capability 执行中 deadline 尚未真正生效
   当前只在 Provider 调用前检查 ExecutionContext 是否过期，CapabilityDefinition.defaultTimeoutMs 还没有约束已经开始的执行。

2. 人工转接尚无完整自动补建
   售后平台结果未知时已不再宣告成功，并会写入 memory/JDBC PendingHandoffStore；但调用前 PREPARED、领取租约、查询核对和后台补建尚未实现。

3. 完整安全审计平台尚未建设
   模型原文日志已默认关闭，MCP 普通调用也已完成最小化审计；但 Filter 层安全事件、集中式审计存储、
   保留期、访问控制、告警和第三方 Prompt 数据最小化仍需继续建设。

### 9.2 数据与多实例

1. 默认测试没有真实 MySQL/Testcontainers，JDBC 主要使用 SQL 替身验证。
2. JdbcDatabaseClient 仍通过 DriverManager 每次新建连接，没有 HikariCP。
3. 建表由 CREATE TABLE IF NOT EXISTS 完成，没有 Flyway/Liquibase。
4. Session 物理删除后版本重置，存在 generation/ABA 风险。
5. Provider 副作用与幂等结果落库之间仍有崩溃窗口。
6. 退货表缺少 order_id 唯一约束。
7. COUNT(*) + 1 生成业务编号不适合跨实例并发。
8. Monitor 指标仍为单 JVM 内存聚合。

### 9.3 MCP 与动态扩展

1. 熔断器是基础实现，没有严格 HALF_OPEN 单探针、退避和抖动。
2. Gateway 当前每个 Capability 只允许一个 Provider，没有自动故障转移策略。
3. 远程工具发现可以刷新缓存，但不能在运行中原子替换整个 Gateway Catalog。
4. MCP 签名用户委托和细粒度 capability scope 尚未实现。

### 9.4 Docker 与部署

1. Dockerfile 当前没有复制根目录 skills，容器构建时可能打包不到 9 个 Skill Package。
2. Compose 当前只包含 Agent 和 Qdrant，没有 MySQL service。
3. Compose 没有应用容器 healthcheck。
4. 尚未完成真实容器启动、数据库掉线、恢复和重启续接验收。

### 9.5 架构遗留

1. CustomerServiceSkill、ToolManager、ToolRegistry 和 LegacySkillWorkflowAdapter 仍作为回滚兼容层存在。
2. 当前是单 Maven Module，包边界已经形成，但还没有独立发布需求，因此暂未拆多 Module。
3. 专家域 Multi-Agent 路由当前主要记录 agent_routed Trace，真正执行选择仍由 WorkflowRouter 完成。

## 10. 下一步生产化路线

建议顺序：

1. 把人工工单 Outbox 前移为调用前 PREPARED，并建立领取、核对和补建流程。
2. 补 Capability deadline 传播和“结果未知”语义。
3. 加入 MySQL Testcontainers 集成测试。
4. 为退货 order_id 增加唯一约束，替换 COUNT(*) + 1。
5. 引入 HikariCP 和 Flyway。
6. 修复 Docker Skill 打包，加入 MySQL 和应用 healthcheck。
7. 处理 Session generation/ABA。
8. 建设集中式安全审计、保留期、访问控制和告警。
9. 为外部 HTTP 增加有限重试、退避、熔断和 bulkhead。
10. 逐步删除 Legacy Skill 和 ToolRegistry 兼容路径。

## 11. 面试中的 STAR 讲法

### 背景

最初项目是一个能够识别客服意图、调用工具和生成回复的 Java Agent，但订单、退货、Prompt、工具和流程逐渐集中在 CustomerServiceSkill 和 AgentRuntime 中。继续增加业务会导致核心类不断膨胀，也难以单独测试和替换远程能力。

### 任务

在不一次性推翻旧功能的前提下，把项目改造成可扩展、可回滚、可持久化和可审计的 Agent 架构，并证明新业务可以不修改核心 Runtime。

### 行动

- 先冻结原有测试和行为基线。
- 定义 Agent Skill Package、AgentWorkflow、ActionHandler、CapabilityProvider 等稳定接口。
- 用退货申请作为多轮和副作用试点，再逐个迁移其他业务。
- 建立 Local/MCP 统一契约和 Capability allowlist。
- 加入 Session CAS、幂等租约、状态迁移、JWT/RBAC 和 Trace 脱敏。
- 用 WarrantyQuery 做扩展证明，并持续补充架构边界与故障注入测试。

### 结果

- 形成 9 个 Skill Package、8 个独立业务 Workflow 和 9 个稳定 Capability。
- 新增 WarrantyQuery 时不需要修改 Runtime、Router 和 Gateway 的业务分支。
- 默认测试从早期约 115 项增长到 383 项并保持全绿。
- 建立 36 条离线评测样本、JSON 报告、历史趋势和反馈回归闭环。
- 同时保留 Legacy 回滚路径，避免一次性重写带来的高风险。

## 12. 项目介绍话术

### 12.1 30 秒版本

我做了一个基于 Spring Boot 和 LangChain4j 的电商客服 Agent。它不是简单调用大模型，而是把订单、退货、商品、政策问答和人工升级拆成独立 Workflow，再通过 Action Handler 和 Capability Gateway 调用本地或 MCP 远程能力。项目还实现了多轮会话持久化、幂等防重、JWT/RBAC、RAG、Trace/Monitor 和离线评测。目前有 9 个 Skill Package、8 个独立业务 Workflow，默认 383 项测试通过。

### 12.2 2 分钟版本

这个项目最初是一个规则加大模型的客服 Demo，随着订单、商品、退货和人工转接增加，核心 Skill 和 Runtime 逐渐承担太多职责，所以我做了一次渐进式架构改造。

现在 Runtime 只负责通用执行循环；Workflow 负责多轮状态机；Action Handler 负责追问、确认、LLM 和能力调用；Capability Gateway 统一处理身份、幂等和 Provider 分发。业务能力使用稳定 Capability ID，本地 Provider 和 MCP Provider 共用同一份 Schema，所以 Workflow 不需要知道能力是本地还是远程。

在可靠性方面，我把会话状态拆成 SessionStore 内存和 JDBC 实现，通过版本 CAS 防止丢失更新；对退货这类副作用增加幂等 Store、租约、续租和结果回放，并测试了 Provider 成功但 Session 保存失败后的重试不重复执行。安全上使用 Spring Security 校验 JWT 和角色，同时以 tenant、user、conversation 作为所有权边界，对 Trace 做统一脱敏。

质量方面，项目有 36 条离线评测样本、Monitor 五类 Turn 结果、用户负反馈转 Eval 候选的闭环，以及 383 项默认测试。当前还没有完成真实 MySQL 容器验收、完整 deadline 和 Outbox 自动处理，所以我会明确把它定位为工程化学习项目，而不是宣称已经完全生产级。

### 12.3 5 分钟讲解顺序

1. 先讲业务范围，不要一开始堆技术名词。
2. 说明原架构为什么会变难维护。
3. 画出 Runtime、Workflow、Handler、Capability 四层。
4. 用“我要退货”的多轮流程讲一遍真实调用链。
5. 用 Session CAS 和 Capability 幂等解释状态与副作用是两个问题。
6. 用 Local/MCP Provider 说明扩展性。
7. 用 Trace、Monitor 和 Eval 说明如何验证效果。
8. 最后主动说两个最重要不足和下一步，不要等面试官拆穿。

## 13. 高频面试问答

### Q1：这个项目和普通 ChatBot 有什么区别？

普通 ChatBot 的主要流程是“消息进、模型出”。这个项目把任务路由、多轮状态、工具调用、确认、权限、幂等和失败恢复放进可测试的工程链路。LLM 主要负责开放表达和回复生成，确定性业务由 Workflow 和 Capability 控制。

### Q2：为什么要拆 Runtime、Workflow、Action Handler 和 Capability？

它们分别对应执行循环、业务决策、动作执行和原子能力。拆开后可以单独测试状态机、替换 Provider、增加新 Action，也能防止 Runtime 随业务增长形成大量业务分支。

### Q3：项目中的 Skill 和通用 Agent Skill 有什么关系？

当前 Agent Skill Package 已经接近通用 Agent Skill，包含 manifest、SKILL.md、Prompt、references 和能力白名单；但可执行业务流程由 Java Workflow 承担。早期 CustomerServiceSkill 是 Legacy Java 决策类，确实和通用 Agent Skill 概念有混淆，现在已经通过命名和职责分离缓解。

### Q4：为什么不用 LLM 自由决定所有步骤？

退货确认、订单归属和权限校验属于高风险确定性流程。如果完全交给模型，行为难以复现，也难做回归测试。项目采用规则和 Workflow 固定关键路径，只把模糊意图和自然语言回复交给 LLM。

### Q5：怎样证明架构真的可扩展？

项目新增 WarrantyQueryWorkflow 和 warranty-query Skill Package 时，没有在 AgentRuntime、WorkflowRouter、ActionHandlerRegistry 或 DefaultCapabilityGateway 中添加 warranty 业务分支，并用 WarrantySkillExtensionProofTest 检查这个边界。

### Q6：多轮会话是怎样恢复的？

ConversationState 保存活跃 Workflow ID、全局状态和 Workflow 私有 payload。ConversationStore 从 SessionStore 加载快照后，经 WorkflowStateMigrationRegistry 恢复，再由 Router 优先选择未完成 Workflow。因此用户下一轮只说“ORD-003”时，系统知道它是在补充退货订单号。

### Q7：为什么既要本地锁又要 CAS？

本地锁保护同一 JVM 内“读状态、执行业务、保存状态”的完整临界区；CAS 保护多个实例同时读取旧版本后的最终提交。两者解决的范围不同，不能互相替代。

### Q8：Session CAS 能保证退货不会重复提交吗？

不能。两个实例可能都在 CAS 前调用了 Provider，最后只有一个状态保存成功。副作用防重需要独立的幂等 Store，在 Provider 调用前竞争执行权，并保存结果供后续回放。

### Q9：你的幂等为什么还不是 exactly-once？

Provider 成功和幂等完成记录落库不是原子事务。应用在两者之间崩溃时，租约过期后仍可能重试 Provider。生产上还需要下游幂等、唯一业务约束、事务或 Outbox。

### Q10：为什么要设计 Capability，而不是直接调用 Tool？

Capability 是跨 Workflow、本地 Provider、MCP Server 和测试共享的稳定契约。它统一 ID、Schema、身份要求、风险和结果语义；Tool 更像早期进程内实现细节。这样 Workflow 不会依赖某个 Java 方法名。

### Q11：MCP 在项目中解决什么问题？

MCP 让同一 Capability 可以通过标准 tools/list 和 tools/call 暴露给远程 Agent，也让本项目通过 HTTP Client 调用远程能力。它解决的是能力发现和远程调用，不负责业务状态机，也不自动带来安全委托。

### Q12：RAG 为什么同时保留本地模式和 Qdrant 模式？

本地模式可解释、无外部依赖，适合 CI 和基础 Demo；Qdrant 模式用于演示真实 embedding 检索。高级依赖失败时回退本地模式，可以保证知识问答仍有基本可用性。

### Q13：如何降低 LLM 幻觉？

高风险流程使用确定性 Workflow；政策问题先检索知识库，并在 Prompt 中要求只根据召回内容回答；能力结果使用结构化 DTO；危险承诺进入回复质量规则和 Judge 评测。当前仍不能保证开放表达零幻觉。

### Q14：模型调用失败怎么办？

LlmActionHandler 捕获异常，记录 llm_failed，并根据工具结构化结果生成模板化降级回复。未知 Handler 异常则进入 FAILED 生命周期，不会伪装成正常完成。

### Q15：怎样保护用户数据？

身份只来自已验证 JWT Principal；会话 key 包含 tenant、user 和 conversation；读取不存在或不属于自己的资源时不泄露归属信息；公开 Trace 会统一遮蔽敏感 key、省略自由文本并限制容量。模型原文日志已默认关闭，MCP 审计也已最小化，但第三方 Prompt 数据最小化仍需继续治理。

### Q16：如何判断 Agent 质量？

项目分层验证路由、知识召回、回复规则、真实 Agent 链路和 LLM-as-Judge；运行后生成 summary、history 和 trend JSON。在线 Monitor 再聚合工具成功率、LLM 失败率、Turn 结果和用户反馈。当前样本规模较小，结果只表示回归基线。

### Q17：Liveness 和 Readiness 为什么分开？

Liveness 只判断 JVM 是否活着；Readiness 判断实例当前是否适合接收流量。数据库掉线时不应反复重启健康 JVM，所以 liveness 仍为 200，而 JDBC readiness 返回 503。

### Q18：项目最难的问题是什么？

可以重点讲“Provider 已成功但 Session 保存失败”。难点在于用户重试时既要恢复会话，又不能重复产生副作用。项目通过稳定 Workflow 实例生成幂等键，把 Provider 结果保存在幂等 Store，重试时回放结果并只补写 Session。

### Q19：为什么现在不拆 Maven 多 Module？

当前主要问题是职责和依赖边界，而不是独立发布。先通过 package、SPI、注册表和架构测试稳定边界，避免为了形式拆 Module 增加构建复杂度。等 Capability、MCP Server 或 Eval 确实需要独立发布时再拆更合理。

### Q20：如果给你两周继续做，你先做什么？

先把已有 PendingHandoffStore 补成调用前 PREPARED、领取和补建闭环，再补 Testcontainers MySQL、退货唯一约束和 Docker 健康验收。这些比继续增加新客服意图更能降低生产风险。

## 14. 面试演示路线

### 14.1 演示前准备

- 使用本地 memory 模式，减少外部依赖。
- 准备 CUSTOMER、ADMIN 和 MCP_CLIENT 三种 Demo JWT。
- 提前运行 mvn test，确认 383 项通过。
- 提前打开首页、Monitor、Eval 和 Trace 面板。
- 不在现场首次尝试真实模型、Qdrant 或外部售后平台。

### 14.2 推荐 8 分钟演示

1. 订单查询
   输入“帮我查一下订单 ORD-001”，展示 Capability 调用和 Trace。

2. 多轮退货
   依次输入“我要退货”“ORD-004”“质量问题”“确认提交”，展示 Workflow 状态、确认和幂等。

3. 会话恢复
   在等待原因时说明 Session 保存内容和 Router 粘性，不必现场重启数据库。

4. RAG
   输入“超过七天还能退吗”，展示召回文档和 knowledge Trace。

5. 人工升级
   输入“我要投诉并找人工”，展示 EscalationWorkflow 和工单号。

6. 安全
   用 CUSTOMER token 访问 ADMIN Monitor，展示 403；说明 MCP_CLIENT 不能冒充用户。

7. 质量闭环
   展示 Monitor 五类结果和用户负反馈生成 Eval 候选。

8. 测试
   展示 383 项测试结果和评测 summary，不要只展示聊天页面。

### 14.3 演示时不要做

- 不要把 Fake Judge 的 33.3% 通过率讲成模型质量很差或很高；它是预设用例链路验证。
- 不要现场修改数据库数据证明并发。
- 不要声称 memory 模式能够多实例运行。
- 不要展示真实 API key、完整 JWT、Prompt 或用户隐私。
- 不要只演示聊天结果而不解释 Trace 和状态。

## 15. 推荐代码阅读顺序

1. src/main/java/com/smartcs/smart_cs_agent/agent/CustomerServiceAgent.java
2. src/main/java/com/smartcs/smart_cs_agent/agent/AgentRuntime.java
3. src/main/java/com/smartcs/smart_cs_agent/workflow/core/WorkflowRouter.java
4. src/main/java/com/smartcs/smart_cs_agent/workflow/orderquery/OrderQueryWorkflow.java
5. src/main/java/com/smartcs/smart_cs_agent/workflow/returnapply/ReturnApplyWorkflow.java
6. src/main/java/com/smartcs/smart_cs_agent/agent/handler/core/ActionHandlerRegistry.java
7. src/main/java/com/smartcs/smart_cs_agent/agent/handler/defaults/CapabilityActionHandler.java
8. src/main/java/com/smartcs/smart_cs_agent/capability/core/DefaultCapabilityGateway.java
9. src/main/java/com/smartcs/smart_cs_agent/agent/context/ConversationStore.java
10. src/main/java/com/smartcs/smart_cs_agent/session/jdbc/JdbcSessionStore.java
11. src/main/java/com/smartcs/smart_cs_agent/capability/idempotency/jdbc/JdbcCapabilityIdempotencyStore.java
12. src/main/java/com/smartcs/smart_cs_agent/mcp/server/McpServerAdapter.java
13. src/main/java/com/smartcs/smart_cs_agent/mcp/client/HttpMcpClientTransport.java
14. src/main/java/com/smartcs/smart_cs_agent/security/HttpSecurityConfiguration.java
15. src/main/java/com/smartcs/smart_cs_agent/monitor/MonitorService.java

阅读时始终带着三个问题：

- 这个类负责什么？
- 它为什么不能直接依赖下一层的具体实现？
- 它发生异常时，状态、用户回复和 Trace 分别会怎样？

## 16. 面试前速记卡

### 项目数字

- Java 17 + Spring Boot 3.3 + LangChain4j 0.35。
- 9 个 Skill Package。
- 8 个独立业务 Workflow。
- 9 个 Capability ID。
- 36 条离线评测样本。
- 383 项默认测试。
- 5 类 TurnOutcome。
- memory/JDBC 双持久化模式。

### 架构主线

~~~text
Controller
-> Agent
-> ConversationStore
-> Runtime
-> Router
-> Workflow
-> Action Handler
-> Capability Gateway
-> Local/MCP Provider
~~~

### 三个最强亮点

1. 新业务扩展不修改 Runtime 核心业务分支。
2. Session CAS 与副作用幂等分层解决不同一致性问题。
3. Trace、Monitor、用户反馈和 Eval 形成可验证质量闭环。

### 三个必须主动承认的不足

1. 真实 MySQL/Testcontainers 和容器重启验收尚未完成。
2. Capability 执行中 deadline 和 pending 工单持久化尚未完成。
3. 幂等仍有 Provider 成功到完成记录落库之间的崩溃窗口。

### 最后一句总结

这个项目最有价值的部分不是“接入了大模型”，而是把一个容易堆成大类的客服 Demo，逐步改造成了职责清晰、能够扩展、可以恢复、具备安全边界并且有自动化证据的 Agent 工程。
