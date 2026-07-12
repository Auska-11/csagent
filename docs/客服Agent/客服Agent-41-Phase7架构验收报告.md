# 客服Agent-41：Phase 7 架构验收报告

## 1. 验收结论

Phase 7 的“生产业务路由迁移”通过验收。

订单查询、退货查询、商品咨询、通用政策问答、用户资料和人工升级已经分别由独立
`Agent Skill Package + Executable Workflow` 接管。新增业务没有在 `AgentRuntime`、`WorkflowRouter`、
`WorkflowRegistry` 或 `DefaultCapabilityGateway` 中加入业务分支。

这里的通过不等于可以立即删除 Legacy。`CustomerServiceSkill`、`ToolRegistry` 和 Adapter 仍保留一个
稳定观察期，删除属于 Phase 9，必须满足本文第 8 节条件。

## 2. 已完成的生产 Workflow

| Skill Package | Workflow | 主要 Capability | 迁移说明 |
| --- | --- | --- | --- |
| `order-query` | `OrderQueryWorkflow` | `order.lookup`、`order.list-by-user` | 客服Agent-35 |
| `return-query` | `ReturnQueryWorkflow` | `return.lookup` | 客服Agent-36 |
| `product-advisory` | `ProductAdvisoryWorkflow` | `product.search`、`product.lookup` | 客服Agent-37 |
| `policy-qa` | `PolicyQaWorkflow` | `knowledge.search` | 客服Agent-38 |
| `user-profile` | `UserProfileWorkflow` | `user.lookup` | 客服Agent-39 |
| `escalation` | `EscalationWorkflow` | `handoff.request` | 客服Agent-40 |

`return-apply` 和 `warranty-query` 在 Phase 7 前半段已经作为试点与扩展证明运行。当前一共加载 9 个
Skill Package，其中 `customer-service` 是 Legacy 回滚包。

## 3. 核心扩展目标验证

### 3.1 新业务不修改 Runtime

`AgentRuntime` 只执行统一循环：

```text
WorkflowRouter -> AgentWorkflow -> AgentAction -> ActionHandler -> ActionExecutionResult
```

订单、商品、退货、资料、政策和人工升级的名称都不需要进入 Runtime 的动作分发逻辑。
`NewArchitectureBoundaryTest` 会持续检查核心文件不能直接 import 具体 Workflow。

### 3.2 Workflow 与 Skill Package 职责分离

每个 `manifest.yaml` 只声明：

- Skill ID、版本、触发示例和描述。
- 绑定的稳定 Workflow ID。
- 最小 Capability allowlist。
- 风险和确认策略。
- Prompt 与 reference 文件。

多轮步骤、状态迁移、结构化结果判断和客服回复仍由 Java Workflow 负责。这样 `SKILL.md` 不会再次
变成难以测试的隐藏状态机，Java 类也不会承担 Prompt 资料管理。

### 3.3 本地与 MCP 契约一致

Phase 7 建立或完成了以下稳定输出契约：

```text
OrderSummary / OrderListResult
ReturnStatusSummary
ProductSummary / ProductSearchResult
UserProfileSummary
HandoffTicketSummary
KnowledgeSearchHit
```

本地旧 DTO 由兼容 Provider 或独立 Provider 转换；远程 JSON 由 `McpCapabilityProvider` 恢复。Workflow
只读取 `capability.api.contracts`，不认识 Tool DTO、HTTP DTO 或 JSON Map。

### 3.4 独立 Provider 可自动覆盖 Legacy 工具

独立 Provider 实现 `LegacyCapabilityOverride` 并声明自己接管的 Capability。生产 Spring 装配会自动
收集这些声明，`ToolRegistryCapabilityProvider` 隐藏同名旧工具。

新增 Provider 不需要向兼容 Adapter 添加一个新的业务布尔开关。Legacy Adapter 中保留的参数翻译和
DTO 转换只服务回滚与纯 Java 旧测试，不进入新 Workflow。

## 4. 安全边界验证

| 边界 | 当前实现 |
| --- | --- |
| 私有用户资料 | 只信任已认证 `ExecutionContext.principal`，手机号脱敏，不返回 token |
| 单个订单 | Provider 校验订单属于当前 Principal |
| 退货查询 | 先关联订单，再校验订单归属 |
| 我的订单 | 忽略模型生成的 `user_id`，由 Principal 注入 |
| 人工工单 | 身份与会话由 ExecutionContext 注入，不接受模型参数控制 |
| 副作用 | 退货申请有确认与幂等；人工工单有明确升级信号与幂等 |
| Skill 权限 | Handler 执行前检查 manifest allowlist |

人工升级额外验证了一个重要负向场景：“什么情况可以转人工”只查询政策，不创建工单。

## 5. 多轮与全局中断验证

Router 在有非终态 Workflow 时优先恢复，保证用户只回复订单号、退货原因或商品名称时不会丢失任务。

共享 `GlobalInterruptPolicy` 现在统一处理：

- 取消当前任务。
- 明确人工请求。
- 投诉。
- 赔偿、维权和法律纠纷。

例如订单 Workflow 正在等待订单号时，用户改说“我要投诉你们”，当前 Workflow 会先清理自己的私有
状态，再产生 `HandoffAction(COMPLAINT)`。共享 `HandoffSummarySupport` 会保留用户当前原话和已经
收集的 Workflow 状态，使人工工单拿到的信息不只存在于 Trace。这条链路没有在 Router 中增加订单
或投诉特判。

## 6. 自动化验证结果

本批次最终执行：

```text
mvn test
Tests run: 256, Failures: 0, Errors: 0, Skipped: 0

mvn -q -DskipTests package
BUILD SUCCESS，9 个 Skill Package 的 manifest 均位于 Spring Boot JAR
```

关键评测结果：

| 评测 | 结果 |
| --- | --- |
| Agent 链路回复质量 | 100%（3/3） |
| Agent Trace 事件覆盖 | 100%（3/3） |
| 回复质量判断一致率 | 100%（6/6） |
| 知识文档召回率 | 100%（8/8） |
| 客服路由意图命中率 | 100%（16/16） |
| 客服 Action 命中率 | 100%（16/16） |
| 客服工具命中率 | 100%（11/11） |
| 转人工命中率 | 100%（16/16） |

`LLM-as-Judge` 的演示数据通过率仍为 33.3%（1/3），但判断一致率为 100%。该测试使用固定样例验证
评测器行为，不是本次 Workflow 路由的阻塞门槛；真实模型质量仍需单独 Profile 和基线管理。

## 7. 已知限制

1. `CustomerServiceSkill` 仍包含旧业务分支，当前只作为回滚路径。
2. `ToolManager`、`ToolRegistry` 和 `LegacySkillWorkflowAdapter` 尚未删除。
3. 会话、幂等记录和多数 Repository 仍是单进程内存实现。
4. `DemoAuthenticationService` 不是正式 JWT/Session/网关认证。
5. 人工工单远端失败后的 pending 记录没有持久化消息补偿。
6. Trace、Monitor 管理接口和 MCP 管理接口还需要统一 RBAC 与敏感字段审计。
7. 通用意图分类和 SlotExtractor 仍可继续组件化，当前只是人工升级子域完成了共享分类。

这些限制不否定 Phase 7 的可扩展路由验收，但会影响多实例、正式生产安全和长期维护成本。

## 8. Legacy 删除条件

在 Phase 9 删除回滚路径前，至少满足：

1. 新 Workflow 在一个完整稳定期内没有高优先级生产回归。
2. 新旧路由 Eval 对比不低于当前基线，关键负向案例全部保留。
3. 已验证切回 Legacy 的操作流程，确认回滚配置和文档可用。
4. Monitor 能按 Workflow、Capability、业务拒绝和技术失败统计指标。
5. 真实认证进入 Chat 与 MCP 边界，敏感 Trace 审计通过。
6. 副作用幂等和会话状态迁移改为可持久化实现。
7. 删除后全量测试、打包、Demo、健康检查和远程 MCP 契约测试通过。

满足前述条件后，才删除 `CustomerServiceSkill` 同名业务分支、旧 Tool Schema 和 Adapter。删除应作为
独立提交执行，不能和 Phase 8 的生产硬化混在一个难以回滚的大提交中。

## 9. 下一阶段建议

Phase 8 优先级建议：

1. 会话所有权与持久化 SessionStore。
2. 正式 Principal、Chat/MCP/Monitor RBAC。
3. Trace 和管理接口统一脱敏。
4. 分布式幂等、会话锁和故障补偿。
5. Provider 契约测试模板与真实远程 MCP Profile。

在这些基础可靠性问题完成前，不建议为了形式先拆 Maven 多 Module；当前包边界已经能继续验证职责，
多 Module 应在确有独立发布、依赖隔离或构建收益时再做。
