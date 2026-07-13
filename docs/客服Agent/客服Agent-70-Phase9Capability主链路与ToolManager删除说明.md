# 客服 Agent Phase 9 Capability 主链路与 ToolManager 删除说明

## 1. 本批解决了什么

Phase 9.2 删除了旧“大 Skill”执行链，但 Action Handler 仍先调用 `ToolManager`，再由它转发给
`CapabilityGateway`。这会让生产代码同时出现 Tool 与 Capability 两套概念：

```text
改造前：Workflow -> Handler -> ToolManager -> CapabilityGateway -> Provider
改造后：Workflow -> Handler -> CapabilityGateway -> Provider
```

本批把普通能力调用、人工转接和工具目录接口全部改为直接依赖正式 Capability 契约，并删除已经没有
调用方的 `ToolManager`。这不是简单少一层类，而是确定了一条唯一生产主链路。

## 2. 为什么 Handler 应依赖 CapabilityGateway

`CapabilityGateway` 是 Agent 内核与能力实现之间的稳定边界。Handler 只需要表达三件事：

1. 调用哪个稳定能力 ID，例如 `order.lookup`；
2. 传递哪些业务参数；
3. 当前可信的租户、身份、会话、幂等键和超时是什么。

Provider 究竟是本地 Java 实现还是远程 MCP 实现，不应由 Handler 判断。若 Handler 先依赖
`ToolManager`，就会继续把早期本地工具模型带进 Agent 内核，并需要在两套结果对象之间来回转换。

现在 `CapabilityActionHandler` 与 `HandoffActionHandler` 都直接构造 `CapabilityCall`，把
`ExecutionContext` 一起交给 `CapabilityGateway`。Skill Package 的 Capability 白名单校验仍在调用前
执行，未授权请求不会进入 Gateway。

## 3. 为什么只写 capability_result

改造前，普通能力 Handler 同时写两份 Observation：

- `capability`：保存旧 `ToolResult`；
- `capability_result`：保存新 `CapabilityObservation`。

双写会造成两个问题：旧 Workflow 容易长期不迁移；两份结果未来可能不一致。现在 Handler 只写
`capability_result`，数据统一为不可变的 `CapabilityObservation`。它只包含稳定字段：Capability ID、
结果类型、消息、数据、错误码、Provider ID 和幂等回放标记。

`CapabilityObservation.from(CapabilityResult<?>)` 集中完成 Gateway 结果到 Workflow 观察结果的复制。
这样 Workflow 不会持有 Provider 层对象，也不需要知道结果来自 Local 或 MCP。

`ReturnApplyWorkflow` 和 LLM 降级回复已迁移到这个统一结果。测试 Workflow 也使用同一观察键，避免测试
夹具继续掩盖旧契约依赖。

## 4. ToolManager 为什么已经可以删除

删除前先用架构门禁和源码搜索证明：

- `AgentRuntime` 不再提供接收 `ToolManager` 的旧测试构造器；
- `ActionHandlerRegistry` 不再提供 `legacyTestRegistry`；
- 两个 Handler 直接依赖 `CapabilityGateway`；
- `/api/tools` 直接读取 `CapabilityGateway.catalog()`；
- 纯 Java Runtime 测试由测试源码中的 `WorkflowTestSupport` 显式组装 Handler。

满足这些条件后，`ToolManager` 只剩自身文件，没有生产或测试调用方，因此可以物理删除。架构门禁把其
生产 import 允许清单固定为空，防止后续为了图方便重新引入。

`ExecutionContext.legacy()` 当前只为不启动认证链的纯 Java 单元测试构造匿名上下文。生产源码允许调用
清单已经收紧为空，正式请求仍必须从 `TurnContext` 构造可信执行上下文。

## 5. /api/tools 的变化

`GET /api/tools` 仍保留原 URL，但返回列表的元素从旧 `ToolDefinition` 统一为
`CapabilityDefinition`。能力 ID 因此使用正式的点号命名，例如：

```text
order_lookup  -> order.lookup
user_orders   -> order.list-by-user
return_apply  -> return.apply
```

输入和输出 Schema 仍然保留。当前阶段尚未承诺这个调试接口的旧 Java 类型或下划线名称是稳定公共契约；
正式 MCP 客户端应使用 MCP tools/list 与 tools/call 协议。

## 6. 这批还没有删除什么

`ToolRegistryCapabilityProvider -> ToolRegistry` 暂时保留。原因不是继续维护双轨主链路，而是
`order.lookup`、`order.list-by-user` 和 `knowledge.search` 尚未全部拥有独立 Local Provider。

下一批会先补齐 Order 与 Knowledge Provider，并为它们建立独立 `CapabilityDefinition` 和契约测试。
所有能力都离开旧适配器后，才能安全删除：

- `ToolRegistryCapabilityProvider`；
- `LegacyCapabilityContractMapper` 与 `LegacyCapabilityOverride`；
- `ToolRegistry`、`ToolCall`、`ToolResult` 和重复旧 Tool Schema。

这个顺序保证每一步都能运行和回滚，不会为了目录整洁丢失真实业务能力。

## 7. 验收结果

本批执行：

```bash
sh mvnw -q -DskipTests test-compile
sh mvnw -q -Dtest=CapabilityActionHandlerTenantTest,HandoffActionHandlerTest,ToolApiControllerTest,ReturnApplyWorkflowTest,Phase9LegacyDependencyBoundaryTest,Phase9WorkflowRoutingBaselineTest test
sh mvnw -q clean test
```

干净全量构建共 508 项测试，失败 0、错误 0、跳过 0。Workflow 路由 Eval 16/16 命中，Agent 链路回复
质量和 Trace 覆盖率均为 100%。

## 8. 初学者可以怎样理解这一批

可以把 Workflow 看成“决定下一步做什么”，Handler 看成“把决定变成一次标准调用”，Gateway 看成
“根据统一目录找到真正执行者”，Provider 看成“具体执行本地代码或远程 MCP 的插件”。

`ToolManager` 同时夹在 Handler 与 Gateway 之间，又暴露旧 Tool 类型，已经不再承担独立职责。删除它后，
每一层只有一个清楚的任务，新增 Provider 不需要修改 AgentRuntime 或 Handler。
