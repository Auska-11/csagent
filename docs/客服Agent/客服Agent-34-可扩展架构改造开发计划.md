# 客服Agent-34：可扩展架构改造开发计划

## 1. 文档状态

| 项目 | 内容 |
| --- | --- |
| 状态 | Phase 7 生产路由迁移已完成；Phase 8 已完成十六批收口，Outbox 已支持租约与后台有限恢复，真实数据库和容器验收仍在继续 |
| 制定日期 | 2026-07-11 |
| 改造方式 | 渐进迁移、双轨兼容、可回滚，不做一次性重写 |
| 改造前基线 | Java 17、Spring Boot 3.3、LangChain4j 0.35；115 个测试通过 |
| 当前验证规模 | 394 个默认测试全部通过；Spring Boot JAR 打包通过，9 个 Skill Package 均已入包 |
| 核心试点 | 退货申请多轮工作流 |
| 最终目标 | 新增一个业务 Skill 时，不修改 AgentRuntime、核心 Router 或 CapabilityGateway |

本文档是客服 Agent 后续架构改造的主执行计划。`docs/开发计划` 中的 v1-v4 保留为项目早期演进记录，不再作为本次架构改造的主要依据。

### 1.1 当前实施结论（2026-07-13）

本轮设定的“核心可扩展架构目标”已经落地：Skill Package 与 Workflow 已分离，Runtime 的 Action
执行已插件化，CapabilityGateway 可以接本地或 MCP Provider，多轮退货 Workflow 具备粘性状态、
身份、确认、并发和幂等保护，并新增 `WarrantyQuery` 作为零核心业务特判的扩展证明。

这里的“核心目标完成”不等于整份长期路线图全部结束。Phase 7 的生产路由迁移已经完成，但旧
`CustomerServiceSkill` 仍保留一个稳定期作为回滚路径；Phase 8 的应用日志与 MCP 普通审计安全已经
收口，售后/Embedding/Qdrant 也已有独立 HTTP 超时；人工转接已具备调用前 PREPARED、结果未知持久化、
成功回放、租约和后台有限补建。但整轮 deadline、售后结果查询与人工复核入口、真实数据库和容器
故障注入，以及 Phase 9 的 Legacy 删除仍是后续演进项，
不能把它们误写成已完成。

## 2. 为什么需要这次改造（改造前问题）

当前项目已经具备比较完整的 Agent 工程骨架：

- `CustomerServiceAgent` 作为应用入口。
- `AgentRuntime` 执行 Action 循环。
- `Skill` 决定下一步动作。
- `ToolManager` / `ToolRegistry` 统一调用业务工具。
- `ConversationState` / `TurnContext` 保存跨轮和本轮状态。
- MCP-style REST Adapter 和 JSON-RPC 风格入口。
- Trace、Monitor、Eval、RAG、记忆和人工转接。

改造启动时的主要问题不是“没有分层”，而是分层边界还没有完全生效：

1. Java `Skill` 实际承担的是可执行 Workflow/Planner，但名称容易与文件型 Agent Skill 混淆。
2. `CustomerServiceSkill` 集中了意图判断、规则路由、槽位、多轮状态、工具选择、Prompt 和回复生成，正在成为新的上帝类。
3. `skills/customer-service/SKILL.md`、manifest 和 references 尚未成为运行时的真实配置来源。
4. `SkillManager` 的 Multi-Agent 目前主要是路由标签，实际仍由同一个 Skill 执行。
5. 每轮只按最新消息选 Skill，不足以支持可靠的多轮 Workflow 粘性状态。
6. `AgentRuntime` 使用 Action 类型分支执行动作，新增 Action 仍需修改 Runtime。
7. Tool 契约、Tool 执行、MCP 协议适配之间仍有耦合，未来本地和远程实现容易出现重复定义。
8. pending 人工工单、整轮 deadline、真实数据库故障注入和部分生产数据一致性边界仍未完全收口。

因此，本次改造的目标不是增加更多目录，而是建立稳定的扩展协议和单向依赖关系。

## 3. 架构改造总目标

### 3.1 北极星目标

> 将 Smart CS Agent 改造成一个模块化、可插拔、可验证的 Agent 平台内核。Agent Skill Package 只表达指令、知识和能力声明；Executable Workflow 负责任务状态机；Agent Runtime 只负责执行循环；Capability Gateway 统一屏蔽本地工具和远程 MCP；领域服务强制保证身份、权限、事务和幂等。

### 3.2 架构改造成功的最终判定

只有同时满足以下条件，才能将本次架构改造标记为成功：

1. 新增一个 `WarrantyQuery` 或同等复杂度的验证 Skill 时，只需要新增：
   - 一个 Agent Skill Package。
   - 一个 Workflow 实现（纯 Prompt Skill 可不提供 Workflow）。
   - 必要的 Capability Provider 实现。
   - 对应测试。
2. 上述扩展过程中不修改：
   - `AgentRuntime`。
   - `WorkflowRouter` 核心选择流程。
   - `ActionHandlerRegistry`。
   - `CapabilityGateway` 核心分发逻辑。
3. 同一个 Capability 可以通过配置在本地 Provider 和 MCP Provider 之间切换，Workflow 无需修改。
4. 多轮任务能绑定当前 Workflow；用户只补充“ORD-004”或“质量问题”时不会重新路由到错误任务。
5. 所有副作用操作具备确认、身份校验和幂等保护。
6. 新旧功能回归测试、架构测试、Provider 契约测试、并发测试和 Eval 全部通过。
7. 默认测试不依赖真实 LLM、MySQL、Qdrant 或远程 MCP，真实集成测试可以通过独立 Profile 执行。
8. 删除旧兼容层后，核心包之间仍满足本文定义的依赖规则。

### 3.3 可量化质量门槛

| 维度 | 最终门槛 |
| --- | --- |
| 回归 | 当前 115 个测试不得减少，现有核心场景行为保持兼容 |
| 新增测试 | Skill Loader、Workflow Router、Action Handler、Capability Provider、MCP Adapter、权限和并发均有测试 |
| 核心覆盖率 | `agent.kernel`、`workflow`、`capability` 行覆盖率目标不低于 80%，分支覆盖率目标不低于 70% |
| 架构约束 | ArchUnit 或等价测试持续验证禁止依赖关系 |
| 扩展证明 | 新增验证 Skill 时核心框架文件修改数为 0 |
| 状态隔离 | 会话以 tenant/user/conversation 组合身份隔离，不发生跨用户串话 |
| Provider 一致性 | 本地与 MCP Provider 通过同一套 Capability Contract Test |
| 可观测性 | 每次路由、Action、Capability、Guardrail、降级和状态迁移均可由 traceId 关联 |
| 安全 | 未认证用户不能调用私有或副作用 Capability；敏感数据不进入公开 Trace |

## 4. 改造范围与非目标

### 4.1 本次改造范围

- Agent Skill Package 运行时加载。
- Executable Workflow SPI 和多轮生命周期。
- Workflow Router 和全局中断策略。
- Action Handler 插件化。
- Capability Catalog、Gateway 和 Provider SPI。
- 本地能力 Provider 与 MCP Client/Server Adapter。
- 身份上下文、能力授权、确认、幂等和事务边界。
- 会话状态隔离、持久化接口和并发控制。
- Trace、Monitor 和 Eval 对新架构的适配。
- 架构测试、契约测试和扩展性验收用例。

### 4.2 当前明确不做

- 不立刻把每个 Skill 拆成独立微服务。
- 不在第一阶段实现动态加载任意第三方 JAR。
- 不先设计通用 Workflow DSL。
- 不为了展示概念强行拆多个重复 Agent。
- 不让本地 Agent 通过 HTTP 调用同进程 MCP Server。
- 不把会话状态放进 MCP Server。
- 不在边界稳定前引入 Kubernetes、服务网格或分布式工作流引擎。

## 5. 统一词汇

| 名称 | 职责 | 不负责 |
| --- | --- | --- |
| Agent | 长期角色，拥有目标、身份、记忆策略和一组 Skill/Workflow | 具体数据库访问 |
| Agent Skill Package | 声明角色说明、触发描述、Prompt、references、允许能力和评测样例 | Java 多轮状态机、数据库调用 |
| Executable Workflow | 实现任务编排、槽位收集、确认、恢复、完成和取消 | MCP 传输、Repository 实现 |
| Agent Runtime | 执行 observe-act 循环、步数预算、Action 调度、Trace 和终止控制 | 客服意图关键词、退货业务规则 |
| Action Handler | 执行一种 Action 并生成 Observation | 决定客服业务流程 |
| Capability | 稳定的原子业务能力契约 | 客服话术和多轮编排 |
| Capability Provider | 提供一个或多个 Capability 的具体实现 | 决定使用哪个业务流程 |
| MCP | Capability 的远程发现和调用协议适配 | 业务状态机、会话记忆 |
| Domain Service | 保证订单归属、退货规则、事务、幂等和领域不变量 | Prompt 和 Agent 路由 |

## 6. 目标架构

```text
ChatApiController
  -> CustomerServiceApplicationService
      -> AgentRuntime                         # 通用执行内核
          -> WorkflowRouter                  # 首次路由 / 恢复活跃任务
              -> WorkflowRegistry
                  -> OrderQueryWorkflow
                  -> ProductAdvisoryWorkflow
                  -> ReturnApplyWorkflow
                  -> ReturnQueryWorkflow
                  -> PolicyQaWorkflow
          -> ActionHandlerRegistry
              -> AskUserActionHandler
              -> LlmActionHandler
              -> InvokeCapabilityActionHandler
              -> ConfirmActionHandler
              -> CompleteActionHandler
          -> CapabilityGateway
              -> LocalCapabilityProvider
                  -> Domain Service
                      -> Repository
              -> McpCapabilityProvider
                  -> Remote MCP Server

AgentSkillPackageRegistry
  -> manifest.yaml
  -> SKILL.md
  -> prompts/
  -> references/
  -> examples/

External MCP Client
  -> McpServerAdapter
      -> CapabilityGateway
```

### 6.1 依赖方向

```text
application
  -> agent-kernel
  -> workflow-api
  -> capability-api

workflows
  -> workflow-api
  -> capability-api
  -> skill-package-api

local-capabilities
  -> capability-api
  -> domain

mcp-adapter
  -> capability-api

infrastructure
  -> domain ports
  -> agent/skill/capability ports
```

禁止反向依赖：

- `agent-kernel` 禁止依赖 `controller`、`repository`、具体 Tools 或客服 Workflow。
- Workflow 禁止直接依赖 Repository、HTTP Client、JDBC、Qdrant 或 MCP Controller。
- Domain 禁止依赖 Agent、Prompt、Skill 或 MCP。
- MCP Adapter 禁止包含订单、退货和客服话术规则。
- Controller 禁止直接执行 Tool 或修改 WorkflowState。

## 7. 目标目录规划

第一阶段仍保持单 Maven Module，只调整包边界；边界稳定后再评估多 Module。

```text
src/main/java/com/smartcs/smart_cs_agent/
├── application/
│   └── CustomerServiceApplicationService.java
├── agent/
│   ├── kernel/
│   │   ├── AgentRuntime.java
│   │   ├── RuntimePolicy.java
│   │   └── RuntimeResult.java
│   ├── action/
│   │   ├── AgentAction.java
│   │   ├── ActionHandler.java
│   │   ├── ActionHandlerRegistry.java
│   │   └── handler/
│   └── context/
│       ├── ExecutionContext.java
│       ├── TurnContext.java
│       └── Observation.java
├── skill/
│   ├── api/
│   │   ├── AgentSkillDefinition.java
│   │   ├── AgentSkillPackage.java
│   │   └── AgentSkillLoader.java
│   ├── loader/
│   │   ├── ClasspathSkillLoader.java
│   │   └── ExternalDirectorySkillLoader.java
│   └── registry/
│       └── AgentSkillPackageRegistry.java
├── workflow/
│   ├── api/
│   │   ├── AgentWorkflow.java
│   │   ├── WorkflowDescriptor.java
│   │   ├── WorkflowDecision.java
│   │   ├── WorkflowSession.java
│   │   └── WorkflowStatus.java
│   ├── router/
│   │   ├── WorkflowRouter.java
│   │   ├── RouteDecision.java
│   │   └── GlobalInterruptPolicy.java
│   └── customer/
│       ├── order/
│       ├── product/
│       ├── returnapply/
│       ├── returnquery/
│       └── policy/
├── capability/
│   ├── api/
│   │   ├── CapabilityDefinition.java
│   │   ├── CapabilityCall.java
│   │   ├── CapabilityResult.java
│   │   ├── CapabilityProvider.java
│   │   └── CapabilityGateway.java
│   ├── registry/
│   │   └── CapabilityCatalog.java
│   └── local/
│       └── ToolRegistryCapabilityProvider.java
├── domain/
│   ├── order/
│   ├── product/
│   ├── returns/
│   └── handoff/
├── adapter/
│   ├── mcp/client/
│   ├── mcp/server/
│   ├── llm/
│   ├── persistence/
│   └── rag/
└── controller/

src/main/resources/skills/
├── customer-service-common/
├── order-query/
├── return-apply/
├── product-advisory/
└── policy-qa/
```

## 8. 核心接口草案

### 8.1 Agent Skill Package

```java
public record AgentSkillDefinition(
        String id,
        String version,
        String description,
        List<String> triggers,
        String workflowId,
        List<String> allowedCapabilities,
        String systemPrompt,
        List<String> references,
        RiskLevel riskLevel
) {
}
```

建议 manifest 最低字段：

```yaml
id: return-apply
version: 1.0.0
description: 收集退货信息、确认并提交退货申请
workflow: return_apply
triggers:
  - 我要退货
  - 申请退款
allowed_capabilities:
  - order.lookup
  - return.apply
risk_level: side_effect
prompt: SKILL.md
references:
  - references/policy.md
  - references/response-guidance.md
```

运行时必须校验：

- Skill ID 和版本是否合法。
- Workflow 是否存在。
- 引用文件是否存在。
- allowed capability 是否存在。
- 同一 ID 是否重复。
- side-effect Skill 是否声明确认策略。

### 8.2 Executable Workflow

```java
public interface AgentWorkflow {

    WorkflowDescriptor descriptor();

    MatchResult match(RoutingContext context);

    WorkflowDecision nextAction(WorkflowContext context);
}
```

Workflow 生命周期：

```text
NEW
  -> RUNNING
  -> WAITING_USER
  -> WAITING_CONFIRMATION
  -> RUNNING
  -> COMPLETED

任何非终态
  -> CANCELLED
  -> FAILED
```

### 8.3 Action Handler

```java
public interface ActionHandler<A extends AgentAction> {

    Class<A> supportedType();

    Observation execute(A action, ExecutionContext context);
}
```

第一版标准 Action：

- `AskUserAction`
- `LLMAction`
- `InvokeCapabilityAction`
- `ConfirmAction`
- `CompleteAction`
- `CancelAction`

Workflow 只发出 `InvokeCapabilityAction`，不发出 `McpToolAction`。Capability 来自本地还是 MCP 对 Workflow 不可见。

### 8.4 Capability Gateway

```java
public interface CapabilityProvider {

    String providerId();

    Collection<CapabilityDefinition> definitions();

    boolean supports(CapabilityId capabilityId);

    CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context);
}
```

```java
public interface CapabilityGateway {

    CapabilityCatalog catalog();

    CapabilityResult<?> invoke(CapabilityCall call, ExecutionContext context);
}
```

Capability 结果必须区分：

```text
SUCCESS             # 能力调用成功
BUSINESS_REJECTED   # 未找到、状态不允许、归属不匹配等业务拒绝
TECHNICAL_FAILURE   # 超时、连接失败、序列化失败等技术故障
```

### 8.5 安全执行上下文

```java
public record ExecutionContext(
        String traceId,
        AuthenticatedPrincipal principal,
        String tenantId,
        String conversationId,
        String idempotencyKey,
        Instant deadline
) {
}
```

禁止让 LLM 或用户请求直接决定敏感 Capability 的 `userId`。工具调用中的用户身份必须由服务端 `AuthenticatedPrincipal` 注入。

## 9. 分阶段实施计划

## Phase 0：冻结基线与特征测试

### 目标

在结构改动前固定当前行为，保证后续可以判断是否发生回归。

### 任务

- [ ] 记录当前 115 个测试、Eval 指标和关键 Demo 流程。
- [ ] 增加架构改造前的 Characterization Tests：
  - [ ] 订单查询。
  - [ ] 用户全部订单。
  - [ ] 商品咨询。
  - [ ] 退货申请多轮流程。
  - [ ] 退货进度查询。
  - [ ] 知识库问答。
  - [ ] 人工转接。
  - [ ] LLM 失败降级。
- [ ] 固定关键 Trace 事件名称和最低字段。
- [ ] 记录当前 API DTO，改造期间优先保持兼容。
- [ ] 增加一个“新增验证 Skill 不应修改核心”的测试夹具目录。

### 验收标准

- `mvn -Dmaven.repo.local=.m2/repository test` 全部通过。
- 关键场景的 Action 顺序、Capability 名称和最终状态有稳定断言。
- 改造期间发生行为变化时，必须明确判断是预期变更还是回归。

### 回滚方式

本阶段只增加测试和文档，不修改生产行为。

## Phase 1：固定词汇、SPI 和安全上下文

### 目标

建立新架构接口，但继续通过 Adapter 复用旧实现。

### 任务

- [x] 新增 `AgentSkillDefinition`、`AgentSkillPackage`、`AgentSkillLoader`。
- [x] 新增 `AgentWorkflow`、`WorkflowDescriptor`、`WorkflowDecision`。
- [x] 新增 `CapabilityDefinition`、`CapabilityCall`、`CapabilityResult`、`CapabilityProvider`。
- [x] 新增 `ExecutionContext` 和 `AuthenticatedPrincipal`。
- [x] 增加 `LegacySkillWorkflowAdapter`，把旧 `Skill` 包装为 Workflow。
- [x] 增加 `ToolRegistryCapabilityProvider`，把旧 `ToolRegistry` 包装为本地 Provider。
- [x] 旧接口先保留并通过 Adapter 标明迁移方向，不立即删除。
- [x] 引入等价的轻量架构依赖测试，后续可升级为 ArchUnit。
- [x] 禁止 Workflow API 直接依赖 Repository、Controller 和旧 Tools。

### 涉及现有文件

- `skill/Skill.java`
- `skill/SkillManager.java`
- `tools/ToolManager.java`
- `tools/ToolRegistry.java`
- `agent/context/TurnContext.java`
- `agent/AgentRuntime.java`

### 验收标准

- 新 SPI 可单独编译和单元测试。
- 旧客服链路仍通过 Adapter 工作。
- 新增的架构测试能主动拦截一次故意构造的非法依赖。
- Runtime 尚未感知具体订单、商品或退货类。

### 回滚方式

通过配置继续使用 Legacy Adapter，不删除旧入口。

## Phase 2：让 Agent Skill Package 真正进入运行时

### 目标

把现有 `skills/customer-service` 从说明文档升级为可加载、可校验、可观察的 Agent Skill Package。

### 任务

- [x] 保持根目录 `skills/` 为唯一源码，并由 Maven 打包到 `classpath:/skills/`，避免维护两份副本。
- [x] 支持两种来源：
  - [x] `classpath`：随应用打包的稳定默认 Skill。
  - [x] `external-directory`：开发或部署时挂载的外部 Skill。
- [x] 实现 manifest 到强类型 DTO 的解析。
- [x] 加载 `SKILL.md` 和 references。
- [x] 实现 references 按 purpose/trigger 选择，不在每轮注入全部文件。
- [x] 启动时校验 Workflow、Capability、文件引用和版本。
- [ ] Trace 增加：
  - [x] `skill_package_loaded`
  - [x] `skill_package_selected`
  - [x] `skill_references_loaded`
  - [ ] `skill_validation_failed`
- [x] 增加启动期 Skill Package 缓存和不可变快照。
- [ ] 可选开发模式只热更新 Prompt/Reference；Java Workflow 变更仍需重启。

当前校验结果（2026-07-11）：Capability、文件引用、SemVer、重复 Skill ID、重复 Workflow 绑定和
manifest.workflow 是否存在均在启动期校验。能力 allowlist 已进入 Runtime 执行链路，未授权调用
不会触达 Provider。

### 验收标准

- 修改 Skill Prompt 后，LLM Context 中能看到新版本。
- 缺少 reference、引用未知 capability 或 manifest 重复时启动失败并给出明确错误。
- 前端 Trace 能看到 Skill ID、版本和实际加载的 reference 名称。
- Skill Package 中未授权的 Capability 会被 Runtime/Guardrail 拒绝。

### 回滚方式

配置 `smartcs.skill.source=legacy` 可继续加载当前统一 System Prompt。

## Phase 3：Workflow Router 与退货申请试点

### 目标

验证真正独立的多轮 Workflow，并解决每轮重新路由的问题。

### 任务

- [x] 新增 `WorkflowRegistry`，通过 Spring `List<AgentWorkflow>` 自动发现实现。
- [x] 新增 `WorkflowRouter`：
  - [x] 有活跃非终态 Workflow 时优先恢复。
  - [x] 没有活跃任务时才进行首次匹配。
  - [x] 匹配分数低于阈值时进入通用流程。
  - [x] 记录 score、reason、候选项和最终决策。
- [x] 新增 `GlobalInterruptPolicy`：
  - [x] 取消当前任务。
  - [x] 切换任务。
  - [x] 用户明确要求人工。
  - [ ] 安全或权限冲突。
- [x] ConversationState 增加：
  - [x] `activeWorkflowId`
  - [x] `workflowVersion`
  - [x] `workflowStatus`
  - [x] `workflowState`
  - [x] `workflowStartedAt`
- [x] Workflow 状态按 Workflow ID 命名空间隔离。
- [x] 抽取 `ReturnApplyWorkflow` 和 `ReturnApplyState`（旧类暂留作功能开关回滚）。
- [x] 增加 `ConfirmAction`：提交退货前向用户展示订单、原因和影响并确认。
- [x] 用户取消后不得调用 `return_apply`。
- [x] 任务完成后清理 Workflow 临时状态，但保留会话历史摘要和终态元数据。
- [x] 并发请求对同一会话采用会话级串行锁。

### 退货试点状态机

```text
START
  -> 缺 orderId -> WAITING_ORDER_ID
  -> 缺 reason  -> WAITING_REASON
  -> 信息完整   -> WAITING_CONFIRMATION
  -> 用户确认   -> INVOKING_RETURN_APPLY
  -> 成功       -> COMPLETED
  -> 拒绝/取消  -> CANCELLED
  -> 技术失败   -> RETRYABLE_FAILED / HANDOFF
```

### 验收场景

- [x] “我要退货” -> 追问订单号 -> “ORD-003” -> 追问原因 -> “质量问题” -> 确认 -> 提交。
- [x] 等待原因时说“我要人工”会打断当前 Workflow，而不是把它记录成退货原因。
- [x] 等待确认时说“取消”不会调用工具。
- [x] 同一 conversationId 被不同 userId 使用时拒绝继续原任务。
- [x] 同一会话由会话锁串行执行；Gateway 合并相同幂等键，领域服务也阻止不同 key 的并发重复退货。
- [x] Workflow 完成后下一条商品咨询会重新路由。

当前实现说明（更新于 2026-07-12）：订单归属通过 `order.lookup` 的服务端结构化结果校验；同会话请求
已经串行化，Gateway 使用稳定 idempotencyKey 合并并发并回放结果，领域服务按订单加锁并拒绝重复申请。
Phase 8 第三批已经增加 JDBC 幂等 Store，使用数据库唯一键、租约和结果回放协调多个实例；领域表
唯一约束和 Provider 副作用与幂等记录的事务一致性仍需继续加强。

### 验收标准

- 退货逻辑不再位于 `CustomerServiceSkill`。
- 多轮补充消息不依赖重新识别完整意图。
- 每次状态迁移都有 Trace。
- 退货申请具备归属校验、确认和幂等键。

### 回滚方式

功能开关 `smartcs.workflow.return-apply-v2.enabled=false` 时回到 Legacy Workflow。

## Phase 4：Action Handler 插件化

### 目标

让 AgentRuntime 不再通过大量类型分支执行 Action。

### 任务

- [x] 新增 `ActionHandler` SPI。
- [x] 新增 `ActionHandlerRegistry`，通过 Spring 自动收集 Handler。
- [x] 为现有行为实现 Handler：
  - [x] `AskUserActionHandler`
  - [x] `LlmActionHandler`
  - [x] `CapabilityActionHandler`
  - [x] `ConfirmActionHandler`
  - [x] `FinalAnswerActionHandler`
  - [x] `HandoffActionHandler`
- [x] Runtime 只负责：
  - [x] 读取下一步 WorkflowDecision。
  - [x] 找到对应 Handler。
  - [x] 根据统一执行结果控制 Observation 后续循环。
  - [x] 控制最大步骤、等待和终止；Capability deadline 由 Handler 写入 ExecutionContext。
- [x] 未注册 Action 使用稳定错误码 `ACTION_HANDLER_NOT_FOUND`。
- [x] Handler 未处理异常转换为安全 `ActionFailureObservation`，不进入用户回复。
- [x] Trace 统一包含 actionId、handlerId、status、durationMs。

### 验收标准

- 新增一个测试 Action 和 Handler 时无需修改 AgentRuntime。
- Runtime 不再 import 具体客服 Action 实现。
- Handler 重复注册或缺失时启动/运行给出明确错误。
- 现有 Action 链路和 Trace 回归通过。

### 回滚方式

保留旧 Runtime Executor 一个版本，通过配置选择执行器。

## Phase 5：Capability Gateway 与单一契约来源

### 目标

把业务能力契约、Provider 选择和执行结果统一起来，消除 Tool/MCP 多处重复定义。

### 任务

- [x] 建立 `CapabilityCatalog`，作为能力元数据唯一来源。
- [ ] Capability ID 使用稳定命名空间，例如：
  - [x] `order.lookup`
  - [x] `order.list-by-user`
  - [x] `product.search`
  - [x] `return.apply`
  - [x] `return.lookup`
  - [x] `knowledge.search`
  - [x] `handoff.request`
- [x] CapabilityDefinition 包含：
  - [x] 版本。
  - [x] input/output JSON Schema。
  - [x] 是否需要身份。
  - [x] 是否有副作用。
  - [x] 是否幂等。
  - [x] 默认超时。
  - [x] 风险级别。
- [x] `ToolRegistryCapabilityProvider` 先包装旧工具。
- [ ] 逐步将 Tool Handler 拆为独立 Local Capability Handler。
- [x] `CapabilityGateway` 负责 Provider 选择和调用。
- [x] Workflow 只能调用 Skill Package allowlist 中的 Capability。
- [x] 身份、tenantId、traceId、conversationId 和 idempotencyKey 从 ExecutionContext 注入。
- [x] 将工具结果改为 `SUCCESS / BUSINESS_REJECTED / TECHNICAL_FAILURE`。
- [x] 对副作用 Capability 增加幂等记录和重复调用检测，并提供内存/JDBC 可替换实现。
- [x] Domain Service 内强制校验订单归属和状态迁移。

### 验收标准

- Local Provider 和 Fake Provider 通过同一套契约测试。
- `return.apply` 重放同一个 idempotencyKey 不会创建两条退货记录。
- “订单不存在”记为业务拒绝，不再计为工具技术成功。
- 未授权 Workflow 无法调用不在 allowlist 中的 Capability。
- Capability Schema 只维护一份。

### 回滚方式

CapabilityGateway 第一版内部仍可委托旧 ToolRegistry，逐个能力迁移。

## Phase 6：MCP Client/Server Adapter

### 目标

在 Capability 边界稳定后，实现本地与远程能力的真正可替换调用。

### 任务

- [x] 将现有 `McpJsonRpcController` 调整为委托 `McpServerAdapter`。
- [x] MCP Server Adapter 只依赖 CapabilityGateway，不依赖具体 Tools。
- [x] 新增 `McpCapabilityProvider`：
  - [x] 初始化连接。
  - [x] 获取远程工具列表。
  - [x] 将远程 Tool 映射为 CapabilityDefinition。
  - [x] 调用远程工具。
  - [x] 标准化错误。
- [x] 本地能力保持直接 Java 调用，不走 HTTP 回环。
- [x] 支持 Provider 配置：
  - [x] local
  - [x] mcp-remote
  - [x] hybrid
- [x] hybrid 远程工具使用 server namespace 防止重名。
- [x] 增加受信服务令牌，并禁止把未认证用户 claim 提升为认证身份。
- [x] 增加连接/读取超时、有限重试、熔断和健康状态。
- [x] 工具发现使用带 TTL 的启动/访问快照；运行中动态改写 Gateway Catalog 留待后续版本。
- [x] MCP 日志记录 serverId、capability、requestId、duration、结果状态，不记录业务 arguments。
- [x] 增加协议契约测试，覆盖 initialize、tools/list、tools/call、认证和错误响应。

### 验收场景

- [ ] `order.lookup` 使用 Local Provider，订单 Workflow 正常工作。
- [ ] 只切换配置改用远程 MCP Provider，Workflow 和 Skill Package 零修改。
- [ ] 远程 MCP 不可用时返回技术失败并按策略降级，不伪装成业务成功。
- [ ] 外部 MCP Client 只能调用已授权、已发布的 Capability。
- [ ] 未认证调用无法执行 `return.apply`、`ticket.create` 等副作用操作。

### 验收标准

- Local/MCP Provider 通过相同 Contract Test。
- AgentRuntime 不 import MCP Controller、MCP Client 或 HTTP DTO。
- MCP Server 可选独立部署，但单体模式仍可直接运行测试和 Demo。

### 回滚方式

配置切回 `local` Provider，不影响 Workflow。

## Phase 7：拆分剩余客服 Workflow

### 目标

逐步删除 `CustomerServiceSkill` 中的领域分支，让每个任务有清晰所有者。

### 建议拆分顺序

1. [x] `OrderQueryWorkflow`（2026-07-12 已完成 Package、状态机、订单契约、安全与集成测试）
2. [x] `ReturnQueryWorkflow`（2026-07-12 已完成 Package、状态机、退货契约、归属校验与集成测试）
3. [x] `ProductAdvisoryWorkflow`（2026-07-12 已完成 Package、商品契约、长期偏好读取与集成测试）
4. [x] `PolicyQaWorkflow`（2026-07-12 已完成 Package、知识归属边界、跨轮主题与集成测试）
5. [x] `UserProfileWorkflow`（2026-07-12 已完成认证 Principal、脱敏契约、偏好读写与集成测试）
6. [x] `ComplaintWorkflow`（2026-07-12 明确合并进 `EscalationWorkflow`，避免复制同一工单流程）
7. [x] `EscalationWorkflow`（2026-07-12 已完成 Package、共享升级分类、稳定工单契约与集成测试）

本轮另外完成了 `[x] WarrantyQueryWorkflow`，作为“新增 Package + Workflow 时核心内核零业务修改”
的验收样例。它不代表上述 Legacy 分支已经全部迁移。

订单查询迁移的调用链、状态机和安全说明见
[`客服Agent-35-订单查询Workflow迁移说明.md`](客服Agent-35-订单查询Workflow迁移说明.md)。

退货查询迁移的关联订单归属校验、稳定契约和回滚说明见
[`客服Agent-36-退货查询Workflow迁移说明.md`](客服Agent-36-退货查询Workflow迁移说明.md)。

商品咨询迁移的长期偏好双轨、商品契约和通用 Override 机制见
[`客服Agent-37-商品咨询Workflow迁移说明.md`](客服Agent-37-商品咨询Workflow迁移说明.md)。

通用政策问答的任务边界、知识归属和人工规则区分见
[`客服Agent-38-通用政策问答Workflow迁移说明.md`](客服Agent-38-通用政策问答Workflow迁移说明.md)。

用户资料的 Principal 防伪、手机号脱敏和偏好所有权说明见
[`客服Agent-39-用户资料Workflow迁移说明.md`](客服Agent-39-用户资料Workflow迁移说明.md)。

人工升级的投诉合并、政策问题负向边界、服务端身份注入和工单契约说明见
[`客服Agent-40-人工升级Workflow迁移说明.md`](客服Agent-40-人工升级Workflow迁移说明.md)。

Phase 7 到此完成“生产路由迁移”。`CustomerServiceSkill` 中对应分支暂时只承担回滚职责，必须等
稳定期、Eval 对比和生产观测均通过后，才在 Phase 9 删除，不能为了目录整洁提前失去回滚能力。

### 共用能力下沉

- [ ] 通用意图分类进入 Router/Classifier，不复制到每个 Workflow（人工升级子域已由 `GlobalInterruptPolicy` 统一）。
- [ ] 槽位提取使用可组合 SlotExtractor。
- [ ] Prompt 组装使用 Skill Package + PromptAssembler。
- [ ] 回复生成使用通用 LLM Action Handler。
- [x] 取消和人工升级中断使用 `GlobalInterruptPolicy`，并传递稳定人工原因码。
- [ ] 超时和安全冲突继续纳入 `GlobalInterruptPolicy`。
- [ ] 用户记忆读写通过 Memory Port，不在 Workflow 中直接操作文件或 Map。

### Multi-Agent 边界

第一阶段仍保持一个 Customer Service Agent + 多个 Workflow。只有满足以下任一条件才拆真正专家 Agent：

- 权限集合不同。
- Persona/System Prompt 明显不同。
- 记忆隔离要求不同。
- 使用不同模型或成本预算。
- 独立扩缩容或独立 SLA。

### 验收标准

- 生产首次路由不再由 `CustomerServiceSkill` 承担订单、商品、退货、资料、政策和人工升级任务。
- `CustomerServiceSkill` 中旧分支只作为 Phase 9 前的显式回滚路径，不再新增业务能力。
- 每个 Workflow 可以独立单元测试。
- Router 测试覆盖歧义、低置信度、活跃任务恢复和全局中断。
- 新增 Workflow 不修改 Runtime 和 Gateway。

### 回滚方式

每拆一个 Workflow 都保留一个阶段的 Legacy Adapter，确认评测无回归后再删除。

## Phase 8：状态持久化、并发、安全和可观测性收口

### 目标

让新架构不仅可扩展，也具备可靠运行的基础条件。

### 任务

- [x] ConversationStore 以 `(tenantId, userId, conversationId)` 作为身份边界。
- [x] 读取、继续和清除会话时验证所有权。
- [x] 增加持久化 SessionStore 实现，内存实现只用于测试和 Demo。
- [x] 同一进程使用 ConversationKey 锁；SessionStore 使用 revision CAS 防止多实例丢失更新。
- [x] Capability 幂等存储拆为 SPI；JDBC 使用唯一键、租约和结果回放阻止正常并发重复执行。
- [x] WorkflowState 已建立 stateSchemaVersion 基线、未来版本失败关闭和注册式逐版本迁移链。
- [x] 长期记忆增加 TTL、容量上限、摘要上限和脱敏。
- [x] Spring Security Resource Server 为 Chat、Memory、Monitor 和 MCP 接口提供统一 JWT Principal。
- [x] 管理类接口已增加 ADMIN / CUSTOMER / MCP_CLIENT 角色权限矩阵。
- [x] Trace 默认不返回原始 Prompt、完整手机号、用户消息全文和工具敏感参数。
- [x] Monitor 区分成功、业务拒绝、技术失败、Guardrail 拒绝和用户取消。
- [x] Agent 在生成 ChatResponse 前抛异常时也记录 Turn 失败指标。
- [x] 健康检查区分 liveness 和 readiness；JDBC 核心表不可读时 readiness 返回 503。
- [x] 模型完整请求/响应日志默认关闭；MCP 成功、失败和安全拒绝统一写入最小化审计日志。
- [ ] 完成并发、重启恢复、超时、熔断和故障注入测试：
  - [x] 进程内会话串行、跨 Store CAS、幂等并发合并和租约恢复测试。
  - [x] ConversationStore/CapabilityIdempotencyStore 重建恢复与 Session 保存失败回放测试。
  - [x] Agent 步数预算、MCP 真实读取超时、有限重试、协议失败、熔断打开和冷却恢复测试。
  - [x] 售后、Embedding 和 Qdrant 独立连接/读取超时及真实慢 HTTP Server 测试。
  - [ ] Capability 执行中 deadline 和 LLM 重试上限测试。
  - [ ] 真实 MySQL 并发、重启、查询超时、readiness 掉线与恢复测试。

### Phase 8 第一批进度（2026-07-12）

已完成会话所有权边界和 Memory API 的最小认证收口，详细设计与测试记录见
`客服Agent-42-会话所有权边界改造说明.md`。

本批次没有把“认证系统覆盖 Chat、Memory、Monitor 和 MCP 管理接口”标记为完成：Chat 和 Memory
已要求 demo token，但 Monitor、MCP 管理接口、真实 JWT/网关认证和多租户 Principal 尚未统一。
第一批提交时，持久化、跨实例并发和 schemaVersion 尚未实现；后续进度记录如下。

### Phase 8 第二批进度（2026-07-12）

已完成 `SessionStore` SPI、`InMemorySessionStore`、`JdbcSessionStore`、显式会话快照、JSON Codec、
会话 revision CAS 和 Workflow 状态恢复，详细说明见
`客服Agent-43-SessionStore持久化与恢复说明.md`。

当前可以在 `smartcs.persistence.mode=jdbc` 时把会话写入 MySQL，并在重建 ConversationStore 后
恢复等待用户或等待确认的 Workflow。CAS 已能阻止旧版本覆盖新状态，但不能单独阻止两个实例在
保存前重复执行副作用。第二批结束时持久化幂等仍未完成；数据库幂等和租约已在第三批补上，
事务锁和真实 MySQL Testcontainers 仍属于后续工作；WorkflowState 迁移器已在第五批完成。

### Phase 8 第三批进度（2026-07-12）

已完成 `CapabilityIdempotencyStore` SPI、内存/JDBC 双实现、数据库唯一键抢占、执行租约与心跳续租、
三态结果持久化、跨 Store 重建回放、用户级作用域和 canonical JSON 参数指纹保护，详细说明见
`客服Agent-44-Capability幂等持久化与租约说明.md`。

正常运行的多个实例同时收到同一副作用请求时，现在只有一个实例进入 Provider。仍需注意：Provider
完成副作用后、幂等结果落库前若进程崩溃，租约过期后仍可能再次调用，因此领域唯一约束、外部系统
idempotencyKey 和事务/outbox 仍是后续生产硬化内容。

### Phase 8 第四批进度（2026-07-12）

已引入 Spring Boot Actuator，新增常驻 `PersistenceHealthIndicator`，并把 liveness 与 readiness
拆成独立健康组。memory 模式 readiness 不访问数据库；jdbc 模式使用一条只读 SQL 检查
`cs_agent_session` 和 `cs_capability_idempotency`。详细说明见
`客服Agent-45-Liveness与Readiness健康探针说明.md`。

数据库运行期掉线时 readiness 返回 503，而 liveness 保持 200；Actuator 只暴露 health 且隐藏
详情。公共 JDBC 客户端同时增加连接、Socket 和 SQL 执行三层超时，避免网络黑洞时 readiness
长期占用请求线程。启动期数据库不可达、真实 MySQL/Testcontainers 和连接池仍属于后续工作。

### Phase 8 第五批进度（2026-07-12）

已新增纯 Java `WorkflowStateMigrator` SPI 和不可变 `WorkflowStateMigrationRegistry`。Spring 会自动
收集各 Workflow 提供的迁移器；注册表在启动期拒绝重复版本、跳版本和链路缺口，在会话恢复时按
`1 -> 2 -> 3` 顺序迁移，并在下一次成功保存时写入当前版本。详细说明见
`客服Agent-46-WorkflowStateMigrator注册链说明.md`。

迁移编排只发生在 `ConversationStore` 会话边界。`AgentRuntime`、`WorkflowRouter`、
`DefaultCapabilityGateway` 和 `ConversationStateCodec` 不包含具体 Workflow 迁移分支。本批没有为了
演示强行把现有业务状态升级到 v2；当前生产 Workflow 仍写 v1，迁移机制由合成链路和端到端测试验证。

### Phase 8 第六批进度（2026-07-12）

已为进程内 `UserMemoryStore` 增加偏好级 TTL、用户总量上限、每用户偏好上限和单条 Unicode 长度
上限。重复表达同一偏好会刷新 TTL；用户按访问顺序淘汰，偏好按最后表达顺序淘汰。写入前会遮蔽
常见凭据、邮箱、中国手机号、身份证号和 12 至 19 位长数字，脱敏后只剩敏感标签和占位符的输入会被拒绝。

会话规则摘要新增配置化长度上限，生成摘要和恢复旧 Session 快照时都会再次脱敏；超长摘要从旧内容
一侧截断，保留最近上下文。`UserProfileWorkflow` 和 Phase 9 前保留的 Legacy
`CustomerServiceSkill` 都只使用 `UserMemoryWriteResult` 返回的安全文本，不再把原始偏好写入 Trace、
Workflow 状态或用户回复。详细说明和测试记录见 `客服Agent-47-长期记忆TTL容量与脱敏说明.md`。

本批仍不是完整隐私治理：长期偏好仍是进程内数据，TTL 采用读写时惰性清理；姓名、地址等上下文型
字段尚未自动识别。公开 Trace 已在第八批统一治理，但实际发送给 LLM 的 Prompt、最近消息持久化和
MCP 审计仍需要独立的数据最小化与脱敏策略。

### Phase 8 第七批进度（2026-07-12）

已接入 Spring Security OAuth2 Resource Server，所有 HTTP 身份统一来自经过 HS256 签名、issuer、
audience、时间、subject 和 tenant claim 校验的 Bearer JWT。`AuthenticatedPrincipal` 现在同时携带
tenantId、userId 和规范化角色；Chat、Memory、Monitor、Eval、Tool、MCP 与 LLM Demo 均由统一
SecurityFilterChain 按角色授权，未知接口默认拒绝。

本地工作台通过可关闭的 `POST /api/auth/demo/tokens` 获取短期 Demo JWT，令牌只保存在页面内存中；
生产必须关闭该签发器并替换开发密钥。反馈提交者、会话 owner 和助手回复都以服务端 Principal 与
ConversationStore 为准，客户端 body 里的旧 userId/assistantReply 只能用于迁移一致性检查。

MCP_CLIENT JWT 只表示“哪个服务正在调用”，不能把 `_meta.userId` 或 `_meta.tenantId` 提升成客户身份。
由于当前尚未实现签名用户委托，MCP_CLIENT 调用要求用户身份或认证的 Capability 会返回
`MCP_DELEGATION_REQUIRED`；HTTP MCP Client 每次请求都通过 Authorization Header 发送服务 JWT，
不再把 token 和用户身份放入 JSON-RPC body。详细说明和测试矩阵见
`客服Agent-48-JWT统一认证与角色权限说明.md`。

第七批完成的是统一 Web 认证与 RBAC 基线，不代表 Phase 8 整体完成。公开 Trace 脱敏已在第八批完成；
非对称密钥/独立 IdP、签名用户委托、令牌撤销、Prompt 数据最小化、失败指标和数据库故障注入仍需
后续批次处理。

### Phase 8 第八批进度（2026-07-12）

已新增统一 `TraceDataSanitizer`，并把 `TraceEvent` 构造函数设为所有 Runtime、内置 Handler 和扩展
Handler 的安全写入边界。Trace 数据会递归处理 Map、集合和数组，对凭据/联系方式等敏感键直接遮蔽，
对 message、Prompt、query、summary、answer、error 等自由文本字段默认省略，并限制字符串长度、
Map 条目数、集合元素数和递归深度。生成结果是不可变深拷贝，调用方后续修改原参数不会改写历史 Trace。

`HandlerTraceSupport.preview()` 已改为先完整脱敏、再按 Unicode code point 截断，避免邮箱或手机号先被
切成正则无法识别的半段。该策略只清洗可观测副本：Capability Provider 和 LLM 仍收到原始业务输入，
最终用户回复也不会被 Trace 策略篡改。Runtime、Router、Gateway 没有新增订单、退货等业务名称特判。

测试覆盖递归结构、敏感键、循环引用、深度/容量、不可变性、模型原始输入、Provider 原始参数、异常
原文、人工转接摘要和真实 HTTP ChatResponse JSON。详细规则、扩展方法与测试矩阵见
`客服Agent-49-Trace统一脱敏与隐私边界说明.md`。

本批完成的是公开 Trace 的统一安全边界，不等于完整 DLP。应用日志、MCP 服务端审计、历史数据库中的
旧 Trace、最近消息持久化，以及真正发送给第三方 LLM/Provider 的数据最小化仍需后续单独治理。

### Phase 8 第九批进度（2026-07-12）

已新增统一 `TurnOutcome` 和只消费结构化 Trace 的 `TurnOutcomeClassifier`。每轮请求只进入
`SUCCESS`、`BUSINESS_REJECTED`、`TECHNICAL_FAILURE`、`GUARDRAIL_REJECTED`、`USER_CANCELLED`
五个互斥桶之一，优先级为 Guardrail > Technical > Cancelled > Business > Success。迁移期旧
`CapabilityOutcome` 仍把部分安全拒绝表示为 `BUSINESS_REJECTED`，Monitor 通过通用安全错误码词义识别，
没有加入订单、退货等客服业务特判。

`ChatApiController` 现在从进入 Agent 起测量整轮耗时。Agent 在构造 `ChatResponse` 前抛出的认证、授权、
Session 冲突和其他运行时异常会先记录稳定 `errorCode`，再原样抛给既有异常处理器，因此 HTTP 状态和
安全错误响应不变；异常 message 和堆栈不会进入 Monitor。Monitor Summary、最近 Turn 和前端面板已展示
五类计数、Turn 成功率、结果和错误码。

真实多轮测试发现，原有 Workflow 只把私有步骤写成 `CANCELLED`，Runtime 最终仍会记录 `COMPLETED`。
本批新增通用 `CancelWorkflowAction` 与 `CANCELLED` ActionFlow，由 Runtime 统一写入 Workflow 终态；
订单、退货、商品、政策和用户资料 Workflow 不再依赖回复文案表达取消。成功转人工继续记录
`HANDED_OFF`，不会误算为用户取消。详细设计和测试矩阵见
`客服Agent-50-Monitor统一Turn结果与异常指标说明.md`。

本批仍保留三个明确边界：Monitor 累计值是单进程内存指标，尚未接 Micrometer/Prometheus；Spring
Security Filter 和 Bean Validation 在进入 Agent 前拒绝的请求不计作 Agent Turn；跨实例聚合、指标持久化
和数据库/网络故障注入仍属于后续批次。提交前默认测试套件共 `355` 项，全部通过。

### Phase 8 第十批进度（2026-07-12）

已把 Action Handler 未知异常从伪 `COMPLETED` 修正为结构化 `ActionFlow.FAILED`，Runtime 会写入
`WorkflowStatus.FAILED` 和终态 Trace，失败 Workflow 下一轮不再恢复。Agent 最大 Action 步数从硬编码
改为 `smartcs.agent.max-iterations`，默认 10 且不能关闭；超限时同时产生 `turn_timeout`、FAILED
Workflow 终态和 Monitor `TURN_STEP_BUDGET_EXCEEDED` 技术失败。

`JdbcSessionStore` 现在在 JSON 编码和 INSERT/UPDATE 前验证 snapshot owner，防止“调用方收到异常，但
错误 owner 数据已经入库”。真实 Agent 故障注入测试还覆盖了更危险的窗口：退货 Provider 已成功、
随后 Session save 失败；重试会使用相同 idempotencyKey 回放第一次结果，只补写 Session，不会再次调用
副作用 Provider。

HTTP MCP Client 新增 JSON-RPC envelope 和 initialize/tools/list/tools/call 结果结构校验，HTTP 200 的空结果
不再伪装成 Capability SUCCESS。401 等确定性客户端错误不重试；网络/读取失败和 5xx 仍按有限次数重试。
熔断器通过可注入时钟验证打开、冷却后探测成功、失败计数清零；本地慢 HTTP Server 验证 read timeout
确实限制等待时间。详细说明见 `客服Agent-51-失败生命周期与可靠性故障注入说明.md`。

本批完成后默认测试套件共 `364` 项，全部通过。但通用 Capability deadline 目前仍只在 Provider 调用前
fail-fast，不能安全强制中止已经开始的副作用；LLM 最坏重试时间、售后/Embedding/Qdrant HTTP 超时、
pending 人工工单持久化、真实 MySQL/Testcontainers、Session 删除 ABA 和领域数据库唯一约束仍未完成，
因此上面的 Phase 8 故障测试总项继续保持未勾选。

### Phase 8 第十一批进度（2026-07-13）

LangChain4j 的 `log-requests` 和 `log-responses` 已从默认开启改为默认关闭，避免完整 Prompt、模型回复、
订单信息和联系方式绕过公开 Trace 的脱敏边界进入普通应用日志。`ApplicationLogSafetyTest` 会直接解析
生产 `application.yml`，防止以后升级依赖或复制配置时误把两个开关重新打开。

`McpServerAdapter` 的认证拒绝、用户委托拒绝、身份元数据冲突、业务拒绝、技术失败和成功调用现在统一
经过 `auditResult`。日志只保留 serverId、requestRef、capability、粗粒度 callerType、provider、outcome、
errorCode、replayed 和 durationMs；arguments、Bearer token、userId、tenantId、conversationId、幂等键及
结果 message 都不会传给日志方法。

外部 requestId 不再原样记录，而是使用 SHA-256 前 16 位作为不可逆关联引用。其他标识会先脱敏，再限制为
96 个 Unicode code point，并把空白、换行、控制字符和非协议标点替换为下划线，防止日志注入与日志放大。
真实 SLF4J 捕获测试覆盖成功、业务拒绝、技术失败和三条安全拒绝路径，详细说明见
`客服Agent-53-应用日志与MCP审计安全说明.md`。

本批完成后默认测试套件共 `370` 项，0 失败、0 错误、0 跳过。完成的是普通应用日志安全默认值与 MCP
调用审计，不等于完整安全审计平台；Spring Security Filter 层事件、集中式审计存储、保留期、访问控制、
告警和第三方 Prompt 数据最小化仍需独立建设。

### Phase 8 第十二批进度（2026-07-13）

`HttpAfterSalesTicketClient`、`OpenAiTextEmbeddingClient` 和 `QdrantKnowledgeVectorStore` 现在通过统一
`BoundedRestClientFactory` 创建生产 RestClient，并分别配置不可关闭的 connect/read timeout。三个依赖
默认连接超时 3 秒、读取超时 10 秒，配置为 0 或负数时会在绑定或创建 Client 时失败，不能用 0 重新打开
“永不超时”。

`application.yml`、`docker-compose.yml` 和 `.env.example` 已同步六个独立环境变量。请求结构单元测试
继续保留 MockRestServiceServer Builder，不会被生产 requestFactory 覆盖；生产构造函数的真实时间边界由
JDK HttpServer 故意延迟 300ms、客户端读取上限设为 50ms 的故障测试验证。

售后工单超时会及时回到现有 REMOTE_HANDOFF_PENDING 降级，Embedding/Qdrant 超时会让
`KnowledgeTools` 回退本地 RAG。但超时只表示客户端停止等待，不代表远端副作用一定没有发生；pending
工单仍需持久化和后台核对，不能把本批描述成已经解决人工转接假成功。详细说明见
`客服Agent-54-外部HTTP客户端超时与故障测试说明.md`。

本批完成后默认测试套件共 `375` 项，0 失败、0 错误、0 跳过。整轮 Capability deadline、LLM 重试上限、
有限重试/退避/熔断、pending 工单 Outbox、真实 MySQL 和容器故障验收仍未完成。

### Phase 8 第十三批进度（2026-07-13）

`HandoffTools` 在售后平台超时或异常时，不再生成仅存在当前 JVM 的 `REMOTE_HANDOFF_PENDING` 工单。
HTTP 超时只能证明客户端没有收到响应，远端可能已经创建工单；在没有可靠 Outbox 和结果查询能力时，
继续向用户回复“已转接”属于无法证明的假成功。

现在结果未知会抛出 `HandoffTicketCreationException`，再由 Capability Gateway 转成不泄露内部异常的
`TECHNICAL_FAILURE`。该失败结果会由原 Capability 幂等 Store 保存；相同 idempotencyKey 再次执行时直接
回放失败，不会盲目重复调用售后平台。`HandoffActionHandler` 会明确告诉用户工单创建失败，并记录
`handoff_failed` Trace，不再出现“已为您转接”的成功文案。

本批完成后默认测试套件共 `377` 项，0 失败、0 错误、0 跳过。这里完成的是保守且可证明的失败语义，
不是可靠自动补建：`PendingHandoffStore`/Outbox、按幂等键查询核对、后台补建和人工复核仍是下一批任务。
详细说明见 `客服Agent-55-人工转接结果未知与假成功修复说明.md`。

### Phase 8 第十四批进度（2026-07-13）

新增独立的 `PendingHandoffStore` Outbox 边界，以及 memory/JDBC 两种条件装配实现。售后平台结果未知时，
`HandoffTools` 会先保存完整远端请求、原 Idempotency-Key、用户与会话上下文、失败类型和状态，再返回技术
失败。JDBC 模式写入 `cs_pending_handoff`，应用重建 Store 后仍能恢复待核对请求。

数据库表以 idempotencyKey 建唯一约束，并保存 SHA-256 请求指纹：相同 key、相同参数会返回已有记录；
相同 key、不同参数会明确冲突，不能覆盖原始证据。异常原文不会进入 Outbox，远端与 Outbox 同时失败时
也只向上返回统一安全异常。

本批完成后默认测试套件共 `383` 项，0 失败、0 错误、0 跳过。当前仍是在捕获远端异常后写记录，
外部调用后、Outbox 写入前的进程崩溃窗口尚未关闭；调用前 PREPARED、成功结果回放、领取租约、CAS 状态
推进和后台核对/补建将在后续批次完成。详细说明见
`客服Agent-56-PendingHandoffStore持久化基础说明.md`。

### Phase 8 第十五批进度（2026-07-13）

人工转接 Outbox 已从“捕获远端异常后记录”前移为调用前 `PREPARED`。`prepare()` 会返回记录是否由当前
执行者首次创建，只有 `created=true` 才能调用售后平台；已有 PREPARED 或 RESULT_UNKNOWN 的同步请求
不会盲目重试，已有 CONFIRMED 则直接回放持久化成功结果。

JDBC 新增追加式 `cs_pending_handoff_result` 表保存真实 ticketId、状态和创建时间。即使进程在结果表写入
后、主表状态更新前退出，重建 Store 仍会把结果解释为 CONFIRMED。外部 Idempotency-Key 也改为基于用户、
会话、原因和 Capability 操作键的 SHA-256，不再暴露用户/会话标识，并允许同一会话先后发起独立操作。

本批完成后默认测试套件共 `389` 项，0 失败、0 错误、0 跳过。调用前崩溃窗口和成功结果回放已经收口，
但多实例领取租约、到期查询、有限补建/退避、远端结果查询和人工复核仍未完成。详细说明见
`客服Agent-57-Outbox调用前准备与成功结果回放说明.md`。

### Phase 8 第十六批进度（2026-07-13）

`PendingHandoffStore` 已新增到期查询、短租约原子领取、过期接管、重试调度、人工复核和 owner 校验。
JDBC 使用独立 `cs_pending_handoff_claim` 唯一表协调多个实例；实例领取后崩溃时，PROCESSING 记录会在
lease 到期后重新进入 due 列表，不会永久卡住。

新增可选 `PendingHandoffRecoveryWorker`，使用 Outbox 原请求和原 Idempotency-Key 调用售后平台；临时
失败按有上限的指数退避进入 RETRY_SCHEDULED，达到最大次数进入 NEEDS_MANUAL_REVIEW。PREPARED 默认
有 30 秒首次执行宽限，避免后台与同步线程并发调用。恢复默认关闭，只有确认下游实现幂等后才允许开启。

本批完成后默认测试套件共 `394` 项，0 失败、0 错误、0 跳过。当前仍缺售后结果查询接口、人工复核
管理 API/UI、租约心跳、历史清理和真实 MySQL 多实例故障验收。详细说明见
`客服Agent-58-Outbox租约与后台有限恢复说明.md`。

### 验收标准

- 不能通过猜测 conversationId 读取或清除其他用户会话。
- 并发调用不会重复执行副作用 Capability。
- 服务重启后可以恢复等待用户或等待确认的 Workflow。
- Monitor 指标能区分成功、业务拒绝、技术失败、Guardrail 拒绝和用户取消。
- Agent 异常路径不会丢失 Turn 指标，也不会把异常原文暴露给 Monitor API。
- 公开 Trace 通过敏感字段与容量边界检查。
- 日志和 MCP 审计通过敏感字段检查。

## Phase 9：清理兼容层与多 Module 评估

### 目标

在新架构稳定后删除迁移代码，避免永久维护两套实现。

### 任务

- [ ] 删除 Legacy Skill/Workflow Adapter。
- [ ] 删除旧 ToolManager 兼容入口。
- [ ] 删除重复的 Tool Schema 和旧路由逻辑。
- [ ] 删除已失效的注释和架构文档。
- [ ] 更新 README 架构图、运行配置和 Demo 流程。
- [ ] 更新 Eval case，记录新旧架构指标对比。
- [ ] 评估拆分 Maven Modules：
  - [ ] `agent-kernel`
  - [ ] `skill-sdk`
  - [ ] `capability-sdk`
  - [ ] `customer-service-workflows`
  - [ ] `local-capabilities`
  - [ ] `mcp-adapter`
  - [ ] `application`
- [ ] 只有 ArchUnit 边界稳定、构建时间和发布需求确有价值时才拆 Module。

### 验收标准

- 主链路中不存在 legacy 开关和双实现。
- 项目文档与真实代码一致。
- 全量测试、Eval、Docker 启动和健康检查通过。

## 10. 横向测试策略

### 10.1 单元测试

- Skill manifest 解析和校验。
- references 选择。
- Workflow 每个状态迁移。
- Router 匹配、阈值和活跃任务恢复。
- Action Handler 成功、业务失败和技术失败。
- Capability allowlist 和参数校验。
- Domain ownership、状态规则和幂等。

### 10.2 契约测试

每个 Capability Provider 必须通过相同测试：

```text
CapabilityProviderContract
  - definitions_are_valid
  - required_arguments_are_enforced
  - identity_is_server_injected
  - business_rejection_is_normalized
  - technical_failure_is_normalized
  - side_effect_is_idempotent
```

### 10.3 集成测试

- AgentRuntime + Workflow + Fake Capability Provider。
- Local Provider + 内存 Repository。
- MCP Client + Fake MCP Server。
- MCP Server Adapter + CapabilityGateway。
- JDBC Profile + Testcontainers/MySQL（可选独立测试任务）。

### 10.4 端到端测试

- API -> Agent -> Workflow -> Capability -> 回复。
- 多轮退货完整闭环。
- 取消和切换任务。
- LLM 失败和 MCP 失败降级。
- 服务重启后恢复 Workflow。
- 不同用户和租户隔离。

### 10.5 架构测试

- Agent Kernel 不依赖具体 Workflow。
- Workflow 不依赖 Repository/Controller/MCP 实现。
- Domain 不依赖 Agent。
- Adapter 只能通过 Port/SPI 进入内核。
- 新增验证 Skill 不修改核心类。

### 10.6 Eval 回归

- 路由准确率不低于当前基线。
- 知识召回指标不低于当前基线。
- 工具/Capability 选择不发生非预期变化。
- 回复安全规则全部通过。
- LLM-as-Judge 继续作为可选测试，不阻塞离线单元测试。

## 11. Feature Flag 与回滚策略

建议改造期间使用以下配置，名称可在实现时统一：

```yaml
smartcs:
  architecture:
    workflow-v2-enabled: false
    action-handler-v2-enabled: false
  skill:
    source: legacy # legacy | classpath | external-directory
    external-path: ./skills
  capability:
    provider: legacy # legacy | local | mcp-remote | hybrid
  mcp:
    remote-url: http://localhost:9001/mcp
```

回滚原则：

- 每个阶段只引入一个新的可替换边界。
- 新旧实现必须共用同一套 Characterization Test。
- 新实现稳定一个阶段后再删除旧实现。
- 不允许 Feature Flag 永久保留；Phase 9 必须清理。

## 12. 风险清单与控制措施

| 风险 | 影响 | 控制方式 |
| --- | --- | --- |
| 抽象过度 | 类数量增加但业务没有更清晰 | 先以 ReturnApplyWorkflow 验证，第二个场景复用后再继续抽象 |
| 大爆炸重构 | 回归难定位 | Adapter 双轨迁移，每阶段独立验收 |
| Skill 只换目录不进运行时 | 形成新的死文档 | Loader、版本、Trace 和校验测试必须同时完成 |
| Workflow 状态泄漏 | 多轮任务互相污染 | Workflow ID 命名空间、生命周期和清理测试 |
| MCP 提前拆服务 | 网络复杂度增加但内部仍耦合 | Capability 边界稳定后再做远程 Provider |
| Tool Schema 漂移 | 本地和远程行为不一致 | Capability Catalog 单一来源 + Contract Test |
| 身份仍由客户端传入 | 越权访问 | AuthenticatedPrincipal 服务端注入 |
| 副作用重复执行 | 重复退货、工单或转人工 | ConfirmAction + idempotencyKey + Domain transaction |
| Prompt/Reference 过大 | Token 成本和延迟增加 | 按需加载 references、预算和缓存 |
| 兼容层长期存在 | 永久维护两套实现 | Phase 9 明确删除门槛 |

## 13. 里程碑与依赖关系

| 里程碑 | 内容 | 复杂度 | 前置依赖 |
| --- | --- | --- | --- |
| M0 | 基线和 Characterization Test | S | 无 |
| M1 | 新 SPI、Adapter、ExecutionContext | M | M0 |
| M2 | Agent Skill Package Loader | M | M1 |
| M3 | Workflow Router + ReturnApplyWorkflow | L | M1、M2 |
| M4 | Action Handler Registry | M | M1、M3 |
| M5 | Capability Gateway + Local Provider | L | M1、M4 |
| M6 | MCP Client/Server Adapter | L | M5 |
| M7 | 剩余 Workflow 拆分 | L | M3、M5 |
| M8 | 持久化、并发、安全、观测收口 | L | M3、M5、M6 |
| M9 | Legacy 清理和多 Module 评估 | M | M7、M8 |

复杂度说明：S 为小范围、M 为中等跨类改造、L 为跨边界且需要完整回归；不将复杂度直接换算成固定日期。

## 14. 每个阶段的完成定义

每个 Phase 必须同时满足：

- [ ] 生产代码已完成。
- [ ] 单元/契约/集成测试按风险补齐。
- [ ] 默认 `mvn test` 通过。
- [ ] 关键 Trace 和 Monitor 指标可观察。
- [ ] 配置和 README/架构文档已同步。
- [ ] 没有引入明文密钥、敏感日志或越权入口。
- [ ] 回滚开关经过一次验证。
- [ ] Git diff 不包含无关文件或用户已有改动。

## 15. 最终 Definition of Done

架构改造目标只有在以下清单全部完成后才可以关闭：

### Agent Kernel

- [ ] AgentRuntime 只包含通用循环、预算、取消、Trace 和终止控制。
- [ ] AgentRuntime 不依赖任何客服具体 Workflow、Tool、Repository 或 MCP 实现。
- [ ] 新 Action 通过注册 Handler 扩展。

### Agent Skill Package

- [ ] manifest、SKILL.md、Prompt、references 被运行时真实加载。
- [ ] Skill Package 有版本、校验、能力 allowlist 和 Trace。
- [ ] 纯 Prompt Skill 和代码 Workflow 可以明确区分。

### Workflow

- [x] 订单、商品、退货申请、退货查询、政策问答已从大 Skill 拆出。
- [x] 活跃 Workflow 支持恢复、取消、完成和失败。
- [x] WorkflowState 隔离、可持久化并具备 schemaVersion 与注册式迁移链。
- [ ] 副作用 Workflow 在执行前确认。

### Capability 与 MCP

- [ ] Capability Catalog 是 Schema 唯一来源。
- [ ] Local/MCP Provider 可配置替换。
- [ ] Workflow 不感知 MCP 传输。
- [ ] 所有 Provider 通过统一契约测试。
- [ ] 副作用能力具备授权、事务和幂等。

### 安全与可靠性

- [x] HTTP 用户身份由已验证 JWT Principal 注入，不信任模型、请求体或 MCP `_meta` 身份字段。
- [ ] 会话、订单、退货、工单和 Monitor 管理操作有所有权/角色校验。
- [ ] 同一会话并发安全。
- [x] 公开 Trace 完成统一敏感信息脱敏与容量限制。
- [x] 应用日志和 MCP 审计完成敏感信息脱敏。

### 测试与扩展证明

- [x] 旧测试全部保留并通过（当前默认套件共 394 个测试）。
- [x] 新增架构、并发、安全和 Provider 契约测试通过。
- [x] 添加 `WarrantyQuery` 验证 Skill 时，核心文件不存在任何 warranty/保修注册或分支。
- [x] Local/MCP Provider 使用同一 Capability ID 与知识输出契约，Workflow 文件无需修改。
- [ ] Docker 启动和健康检查通过。
- [ ] README、架构图、开发计划和真实代码一致。

## 16. 第一批实施任务

下一次开始编码时，严格从以下最小切片开始：

1. [ ] 补充退货申请 Characterization Test，锁定当前行为。
2. [ ] 新建 `workflow/api` 和 `capability/api`，只定义最小接口。
3. [ ] 新建 `LegacySkillWorkflowAdapter` 和 `ToolRegistryCapabilityProvider`。
4. [ ] 新建 ArchUnit 依赖规则测试。
5. [ ] 保持 AgentRuntime 和现有接口行为不变，运行全量测试。
6. [ ] 评审接口是否能同时承载 ReturnApply 和 OrderQuery；通过后进入 Phase 2。

第一批任务不拆 MCP、不改前端、不移动所有文件，目标只是安全建立下一阶段需要的稳定接缝。

## 17. 初学者友好的代码注释与讲解规范

后续进入编码阶段时，代码不仅要能运行，还要让刚接触 Agent 架构的开发者能够沿着注释看懂调用链。所有新代码和被大幅改造的旧代码遵守以下要求。

### 17.1 类级注释

核心类、接口和 Adapter 必须使用 JavaDoc 说明：

- 这个类负责什么。
- 这个类明确不负责什么。
- 它位于调用链的哪个位置。
- 它依赖哪些抽象，为什么不能依赖具体实现。
- 给出一个最小调用示例或典型场景。

示例结构：

```java
/**
 * Workflow 路由器。
 *
 * <p>职责：首次任务时选择 Workflow；存在未完成任务时恢复原 Workflow。</p>
 * <p>不负责：不执行工具、不调用 LLM、不修改订单或退货数据。</p>
 *
 * <p>调用链：AgentRuntime -> WorkflowRouter -> AgentWorkflow。</p>
 *
 * <p>例子：上一轮正在等待退货原因时，用户只回复“质量问题”，
 * Router 应恢复 ReturnApplyWorkflow，而不是重新按关键词选择任务。</p>
 */
```

### 17.2 方法级注释

以下方法必须写清参数、返回值、状态变化和失败方式：

- SPI 接口方法。
- Workflow 状态迁移方法。
- Action Handler 执行方法。
- Capability 调用和 Provider 选择方法。
- 身份、权限、确认、幂等和并发相关方法。

注释重点解释“为什么”和“边界”，而不是把代码逐字翻译一遍。

### 17.3 复杂分支和状态机注释

对多轮 Workflow、降级和并发逻辑，在关键分支前写场景型注释：

```java
// 用户上一轮已经提供订单号，本轮只回复退货原因。
// 这里必须优先恢复活跃 Workflow；如果重新进行意图识别，
// “质量问题”可能会被错误路由为普通售后咨询。
```

状态枚举和迁移方法应说明：

- 当前状态允许进入哪些下一状态。
- 为什么禁止某些跳转。
- 失败后能否重试。
- 什么时候清理临时状态。

### 17.4 安全相关注释

身份和副作用代码必须说明安全原因，例如：

- 为什么 userId 必须从 `AuthenticatedPrincipal` 注入。
- 为什么不能信任 LLM 生成的用户身份参数。
- 为什么退货、工单和转人工需要幂等键。
- 为什么 Trace 不能记录完整手机号、Prompt 或工具敏感参数。

### 17.5 注释质量要求

- 注释必须与代码同步更新，禁止保留已经失效的设计说明。
- 避免“创建对象”“调用方法”一类没有信息量的注释。
- 对简单 getter、明显赋值和普通循环不强制增加注释。
- 优先用清晰命名表达代码，再用注释解释设计原因和业务场景。
- 每完成一个 Phase，在对应设计文档中补充调用链、核心类清单和可运行示例。

### 17.6 注释验收标准

- [x] 新增 SPI 和核心类都有职责/非职责 JavaDoc。
- [x] Workflow 状态迁移有场景说明。
- [x] Capability 权限、确认和幂等逻辑有安全原因说明。
- [x] 复杂降级和并发处理有关键路径注释。
- [x] 收口审计已更新过时的阶段性注释，避免注释与实现冲突。
- [x] 本文第 18 节已列出建议阅读顺序，帮助从入口逐层理解代码。

## 18. 当前真实调用链与建议阅读顺序

### 18.1 一轮请求怎样运行

```text
ChatApiController
  -> Spring Security Resource Server         # 验证 Bearer JWT 的签名与标准/业务 claim
  -> JwtPrincipalConverter                   # JWT -> AuthenticatedPrincipal
  -> CustomerServiceAgent
  -> ConversationStore.executeSerially       # 同一会话串行化、校验 user 所有权
  -> AgentRuntime                            # 通用 observe-act 循环
  -> WorkflowRouter                          # 恢复活跃任务，或为新任务评分
  -> AgentWorkflow                           # 只决定下一步 Action
  -> ActionHandlerRegistry
  -> CapabilityActionHandler                 # Skill allowlist + ExecutionContext
  -> CapabilityGateway                       # 认证、deadline、幂等、Provider 分发
       -> Local Provider -> Domain Service -> Repository
       -> McpCapabilityProvider -> HTTP JSON-RPC -> 远程 MCP Server
  -> CapabilityObservation
  -> AgentWorkflow                           # 根据结果结束、追问或继续
```

### 18.2 初学者建议阅读顺序

1. `agent/CustomerServiceAgent.java`：先看一次请求如何进入 Runtime。
2. `agent/AgentRuntime.java`：理解通用执行循环，不必先看所有 Trace 辅助方法。
3. `workflow/core/WorkflowRouter.java`：重点看“恢复活跃 Workflow”为什么先于重新评分。
4. `workflow/orderquery/OrderQueryWorkflow.java`：带身份校验和多轮追问的只读 Workflow 示例。
5. `workflow/warranty/WarrantyQueryWorkflow.java`：最小只读 Workflow 示例。
6. `workflow/returnapply/ReturnApplyWorkflow.java`：带槽位、确认、取消和副作用的多轮示例。
7. `agent/handler/defaults/CapabilityActionHandler.java`：理解 Workflow 的 Action 如何变成能力调用。
8. `capability/core/DefaultCapabilityGateway.java`：理解身份、deadline、幂等和 Provider 分发。
9. `capability/local/LocalReturnApplyCapabilityProvider.java` 与
   `domain/returnservice/ReturnApplicationService.java`：理解 Adapter 和领域规则的边界。
10. `mcp/server/McpServerAdapter.java`、`mcp/client/McpCapabilityProvider.java`、
   `mcp/client/HttpMcpClientTransport.java`：最后再看远程协议层。

### 18.3 新增业务 Skill 的标准步骤

以 `WarrantyQuery` 为模板：

1. 在根目录 `skills/<skill-id>/` 新增 `manifest.yaml`、`SKILL.md` 和所需 references。
2. 新增一个实现 `AgentWorkflow` 的 Spring Bean；不要去 Router 里添加 switch。
3. 只使用 manifest 的 `allowed_capabilities` 中声明的稳定 Capability ID。
4. 如果现有 Catalog 没有所需原子能力，再新增 `CapabilityProvider`；不要在 Workflow 里访问 Repository。
5. 增加 Workflow 单元测试、Provider 契约测试和一条端到端扩展证明。
6. 确认 `AgentRuntime`、`WorkflowRouter`、`ActionHandlerRegistry` 和
   `DefaultCapabilityGateway` 没有新增该业务名称或特殊分支。

## 19. 本轮核心目标的自动化证据

| 目标 | 自动化证据 |
| --- | --- |
| Skill Package 真正进入运行时 | `AgentSkillPackageLoaderTest`、`AgentSkillPackageRegistryTest` |
| 多轮 Workflow 粘性 | `WorkflowRouterTest`、`ReturnApplyWorkflowIntegrationTest` |
| Runtime 不按 Action 写业务分支 | `ActionHandlerRegistryTest`、`NewArchitectureBoundaryTest` |
| 身份、三态结果和幂等 | `CapabilitySecurityAndIdempotencyTest`、`LocalReturnApplyCapabilityProviderTest` |
| MCP Server/Client 协议 | `McpJsonRpcControllerTest`、`McpToolControllerTest`、`HttpMcpClientTransportTest` |
| JWT 与 HTTP 角色边界 | `HttpSecurityIntegrationTest`、`DemoIssuerDisabledIntegrationTest`、`JwtPrincipalConverterTest` |
| Trace 隐私与容量边界 | `TraceDataSanitizerTest`、`AgentRuntimeTraceTest`、`ChatApiControllerMvcTest` |
| Local/MCP 输出契约一致 | `ToolRegistryCapabilityProviderTest`、`McpCapabilityProviderTest` |
| 新 Skill 零核心业务特判 | `WarrantySkillExtensionProofTest` |
| 人工升级独立路由与工单边界 | `EscalationWorkflowTest`、`EscalationWorkflowIntegrationTest`、`HandoffActionHandlerTest` |

Phase 7 的最终验收结论、核心文件 diff 证据和 Legacy 清理条件见
[`客服Agent-41-Phase7架构验收报告.md`](客服Agent-41-Phase7架构验收报告.md)。

## 20. 仍然存在的不足（后续路线图）

1. Phase 7 的订单、退货查询、商品咨询、政策问答、用户资料和人工升级已经由独立 Workflow 接管
   生产路由；`CustomerServiceSkill` 仍保留同名 Legacy 分支作为稳定期回滚路径。只有 Eval 指标、
   生产 Trace 和回滚演练均通过后，才能在 Phase 9 一次性删除，期间不得继续向旧类添加新业务。
2. `ToolManager`、`ToolRegistry` 和 `LegacySkillWorkflowAdapter` 仍是兼容层；新代码应优先使用
   Workflow/Capability 契约，待回归稳定后再删除旧入口。
3. Conversation 和 Capability 幂等记录已经提供 JDBC 实现，但默认 memory 模式仍只适合单实例；
   Repository 领域锁、业务唯一约束、事务/outbox 和真实数据库并发验证仍需继续补齐。
4. HTTP 已统一使用 JWT Resource Server，但当前单体仍采用共享 HS256 密钥和本地 Demo 签发器。生产应
   关闭 Demo 入口，并迁移到独立 IdP 的 RS256/ES256 或等价非对称验证、密钥轮换和撤销策略。
5. 远程 MCP 工具发现当前主要用于启动期不可变 Catalog。TTL 缓存可刷新 Provider 自身快照，
   但运行中动态增删 Capability 还不能原子替换 Gateway Catalog。
6. Monitor、Eval、Tool 和 MCP HTTP 接口已经接入 RBAC，公开 Trace 和 MCP 普通审计也已统一最小化；
   但远程 MCP 的签名用户委托尚未实现，实际 Prompt/Provider 数据最小化、最近消息、集中式安全审计
   存储和告警仍需系统治理。
7. 项目仍是单 Maven Module。这是当前有意选择；只有包边界稳定且确有独立发布需求时才拆多 Module。

这些不足不影响本轮“可插拔核心接缝 + 扩展证明”的验收，但会影响多实例、动态更新和正式生产安全，
因此不能在后续迭代中忽略。
