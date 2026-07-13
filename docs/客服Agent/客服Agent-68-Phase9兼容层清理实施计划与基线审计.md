# 客服 Agent Phase 9 兼容层清理实施计划与基线审计

## 1. Phase 9 要解决什么

Phase 7 和 Phase 8 已经建立 Workflow、Capability、MCP、Skill Package、安全、幂等和可观测主链路，
但项目仍保留了迁移期兼容代码。它们帮助前几阶段平滑改造，现在却会让初学者看到两套入口，也会让
新功能误接回旧链路。

Phase 9 的目标不是继续增加业务功能，而是让已经实现的架构真正只有一条生产主链路：

```text
CustomerServiceAgent
  -> AgentRuntime
  -> WorkflowRouter -> AgentWorkflow
  -> ActionHandler
  -> CapabilityGateway -> CapabilityProvider
```

完成后，生产代码不再经过 `LegacySkillWorkflowAdapter`、`ToolManager` 或 `ToolRegistry`。Skill Package
继续负责指令、资料和能力授权，Workflow 继续负责状态与决策，两者概念不混用。

## 2. 本阶段不能直接删除的原因

当前 `skills/customer-service/manifest.yaml` 绑定稳定 Workflow ID `customer_service`。这个 ID 对应的
实现仍由 `CustomerServiceSkill -> LegacySkillWorkflowAdapter` 提供，并承接以下输入：

- 闲聊，例如“你好”“谢谢”。
- 模糊意图，例如“我有个问题”。
- 规则无法识别时的 LLM 意图识别和通用回复兜底。

订单、商品、退货申请、退货查询、政策、用户资料、人工升级和保修已经有独立 Workflow。若现在直接删
旧 Skill，明确业务请求大多仍能工作，但闲聊和模糊输入会找不到 `customer_service`，应用还会因为
Skill Package 绑定到未注册 Workflow 而启动失败。

因此删除顺序必须是“先建立替代实现，再移除 Adapter”，不能反过来。

## 3. Phase 9.1 基线审计

### 3.1 Legacy Skill/Workflow 依赖

| 旧类型 | 当前生产调用方 | 清理方向 |
| --- | --- | --- |
| `CustomerServiceSkill` | `CustomerServiceAgent`、`WorkflowBeanConfiguration` | 用新的通用客服 Workflow 替代 |
| `LegacySkillWorkflowAdapter` | `SkillManager`、`WorkflowBeanConfiguration` | 删除无 Router 回退和 Spring 兼容 Bean |

### 3.2 Tool 兼容依赖

| 旧类型 | 当前生产调用方 | 清理方向 |
| --- | --- | --- |
| `ToolManager` | `AgentRuntime` 测试构造器、Handler Registry、两个 Handler、`ToolApiController` | Handler 和 API 直接依赖 `CapabilityGateway` |
| `ToolRegistry` | `ToolRegistryCapabilityProvider` | 原子能力迁完后删除旧工具注册表 |
| `ToolRegistryCapabilityProvider` | `ToolManager` 兼容构造器及 Spring Provider 注册 | 用独立 Local Provider 覆盖全部 Catalog |

`ExecutionContext.legacy()` 当前只被生产 `ToolManager` 的无上下文重载使用。测试中的调用会随测试 Fixture
迁移逐步替换，不允许新的生产入口继续使用匿名 Legacy 上下文。

### 3.3 已冻结的路由行为

| 输入类型 | 必须命中的 Workflow |
| --- | --- |
| 订单查询 | `order_query` |
| 商品咨询 | `product_advisory` |
| 退货申请 | `return_apply` |
| 退货查询 | `return_query` |
| 政策问答 | `policy_qa` |
| 用户资料 | `user_profile` |
| 投诉/人工升级 | `escalation` |
| 保修问答 | `warranty_query` |
| 闲聊和模糊输入 | `customer_service` |

`Phase9WorkflowRoutingBaselineTest` 使用 Spring 的真实 Workflow 注册表验证以上边界，但不会调用外部 LLM
或工具。`Phase9LegacyDependencyBoundaryTest` 则把当前旧类型依赖保存为允许清单，后续只允许缩小。

## 4. 分批执行计划

### 9.1 基线审计与防扩散门禁

- [x] 审计 Legacy Skill/Workflow 和 Tool 兼容依赖。
- [x] 增加生产依赖允许清单测试。
- [x] 增加真实 Workflow 路由行为基线。
- [x] 记录删除顺序、风险和验收标准。
- [x] 517 个默认测试全部通过，失败、错误、跳过均为 0。
- [x] Spring Boot JAR 构建通过。

### 9.2 替换 Legacy Skill/Workflow Adapter

- [x] 新增 `GeneralCustomerServiceWorkflow`，ID 保持 `customer_service`。
- [x] 只承接闲聊、模糊意图和通用 LLM fallback，不重复实现八类业务流程。
- [x] 从 `CustomerServiceAgent` 删除旧 Skill 注册。
- [x] 从 `SkillManager` 删除 Legacy Adapter 回退。
- [x] 删除 `WorkflowBeanConfiguration`、`LegacySkillWorkflowAdapter`、`CustomerServiceSkill` 和旧 `Skill` 接口。
- [x] 迁移旧 Skill 单元测试与 Eval，16 条真实 Workflow 路由 Eval 达到 100%。
- [x] 将通用 Skill Package 收紧为零 Capability，并删除重复意图/政策 reference。
- [x] 修复“一次性退货诉求被订单号路由抢走”的专业 Workflow 边界缺陷。

### 9.3 删除 ToolManager/ToolRegistry 兼容入口

- [x] `CapabilityActionHandler` 和 `HandoffActionHandler` 直接依赖 `CapabilityGateway`。
- [x] 删除 `ActionHandlerRegistry.legacyTestRegistry` 和 `AgentRuntime` 旧构造器。
- [ ] 将旧 `/api/tools/call` 迁移到 Capability/MCP 协议或明确下线。
- [ ] 补齐仍由 `ToolRegistryCapabilityProvider` 提供的原子 Local Provider。
- [ ] 删除 `ToolManager`、`ToolRegistryCapabilityProvider` 与无上下文 Legacy 执行入口。
- [ ] 确认 Capability Catalog/Schema 只有一个权威来源。

第一批已同时删除 `ToolManager`，并将 Workflow Observation 从旧 `ToolResult` 与新结果双写收口为
唯一 `capability_result`。由于 Order 与 Knowledge 独立 Provider 尚未补齐，`ToolRegistryCapabilityProvider`
和 `ToolRegistry` 仍按计划保留到下一批，不能把 9.3 误标为全部完成。实现与测试证据见
[`客服Agent-70-Phase9Capability主链路与ToolManager删除说明.md`](客服Agent-70-Phase9Capability主链路与ToolManager删除说明.md)。

### 9.4 文档、Eval 与 Maven Modules 决策

- [ ] 更新 Eval 样本与测试 Fixture，不再构造 Legacy 类型。
- [ ] 更新 README、架构图、开发路线图和简历面试手册。
- [ ] 统计跨包依赖、独立发布和构建成本，作出是否拆 Maven Modules 的书面决策。
- [ ] 若没有独立发布需求，保持单 Module，并用包边界测试约束；不为“看起来模块化”盲目拆分。

### 9.5 最终验收

- [ ] 全量默认测试通过。
- [ ] 显式真实 MySQL 集成测试通过。
- [ ] Spring Boot JAR 构建与启动通过。
- [ ] Agent Docker 镜像构建通过。
- [ ] Compose 启停、readiness 和关键客服链路通过，验收后停止容器。
- [ ] `rg` 证明生产代码不存在目标 Legacy 依赖。

## 5. 每批提交与回滚原则

每一批必须同时包含代码、测试和本文件状态更新，并形成独立本地 Git 提交，不推送远端。任何一批测试
失败时，只回滚该批架构替换，不修改已稳定的业务、安全、幂等和可观测契约。

稳定 ID 也是兼容契约：替换 `customer_service` 的 Java 实现时，不修改 manifest 绑定和已持久化会话可见
的 Workflow ID。若状态结构发生变化，必须使用现有注册式 `WorkflowStateMigrator`，不能在 Runtime 中写
业务特判。

## 6. Phase 9 完成定义

只有同时满足以下条件，才能宣布架构改造完成：

1. 生产请求只有 Workflow/Capability 主链路，不再经过目标 Legacy 类。
2. 八类业务请求仍由独立 Workflow 处理，闲聊和模糊请求仍有稳定通用兜底。
3. 身份、租户、授权、确认、幂等、deadline、Trace 脱敏和失败语义没有降级。
4. Skill Package 是指令与授权声明，Workflow 是决策状态机，MCP 是能力传输协议，三者边界清晰。
5. Maven Modules 是否拆分有基于发布边界和依赖数据的明确结论。
6. 测试、MySQL、JAR、Docker/Compose 全部验收并留下可复现记录。

## 7. 9.1 验收命令

```bash
sh mvnw -q -Dtest=Phase9LegacyDependencyBoundaryTest,Phase9WorkflowRoutingBaselineTest test
sh mvnw -q test
sh mvnw -q -DskipTests package
```

2026-07-14 的执行结果为：专项测试通过；默认测试共 517 项，`failures=0`、`errors=0`、
`skipped=0`；JAR 构建通过。真实 MySQL 和 Docker/Compose 属于 Phase 9 最终验收，不在只增加测试与
文档的 9.1 重复执行。

## 8. 9.2 验收记录

Phase 9.2 删除 Legacy Skill/Adapter 生产链并迁移测试、Eval 后，干净构建的默认测试共 508 项，失败、错误和跳过
均为 0；Spring Boot JAR 构建通过。详细调用链、最小权限说明、退货路由缺陷和迁移清单见
[`客服Agent-69-Phase9通用客服Workflow与LegacySkill清理说明.md`](客服Agent-69-Phase9通用客服Workflow与LegacySkill清理说明.md)。
