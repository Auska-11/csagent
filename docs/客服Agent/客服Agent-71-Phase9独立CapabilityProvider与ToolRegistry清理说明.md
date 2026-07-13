# 客服 Agent Phase 9 独立 Capability Provider 与 ToolRegistry 清理说明

## 1. 本批完成结果

Phase 9.3 已完成。生产能力调用现在只有一条主链路：

```text
AgentWorkflow
  -> ActionHandler
  -> CapabilityGateway
  -> Local CapabilityProvider 或 MCP CapabilityProvider
  -> Repository / Domain Service / Remote MCP
```

旧链路中的 `ToolManager` 已在上一批删除，本批进一步删除 `ToolRegistryCapabilityProvider`、
`ToolRegistry`、旧 Tool 请求/结果对象和重复 Schema。Agent 内核、REST 调试接口与 MCP Server 现在读取
同一份 `CapabilityCatalog`。

## 2. 九个能力由谁提供

| Provider | Capability | 主要依赖 |
| --- | --- | --- |
| `LocalOrderCapabilityProvider` | `order.lookup`、`order.list-by-user` | Order/Product Repository |
| `LocalProductCatalogCapabilityProvider` | `product.search`、`product.lookup` | Product Repository |
| `LocalReturnApplyCapabilityProvider` | `return.apply` | ReturnApplicationService |
| `LocalReturnLookupCapabilityProvider` | `return.lookup` | Return/Order/Product Repository |
| `LocalUserProfileCapabilityProvider` | `user.lookup` | User Repository |
| `LocalKnowledgeSearchCapabilityProvider` | `knowledge.search` | 关键词/Embedding/Qdrant 检索引擎 |
| `LocalHandoffCapabilityProvider` | `handoff.request` | 人工工单服务 |

每个 Provider 自己声明 `CapabilityDefinition`，包含稳定 ID、版本、输入输出 JSON Schema、身份要求、
是否有副作用、幂等属性、默认超时和风险等级。Gateway 构造时会拒绝重复 ID，因此不能再由 Bean 顺序
悄悄决定谁覆盖谁。

## 3. Order Provider 的安全边界

订单属于用户私有数据。新 Provider 做了三层约束：

1. 两个订单能力都声明 `requiresIdentity=true` 和 `requiresAuthentication=true`；
2. `order.lookup` 在返回任何数据前校验订单所属用户；
3. `order.list-by-user` 忽略参数里可能伪造的 `user_id`，只读取服务端 Principal。

归属失败时 `data=null`，不会在错误响应中泄露商品、订单状态或物流单号。订单号统一转大写后再查询，
但稳定输出仍使用 Repository 中的真实订单号。

## 4. Knowledge Provider 的边界

`KnowledgeTools` 目前仍承担关键词打分、Embedding 和 Qdrant 降级检索，这是一项真实检索职责，并不是
旧 Tool 协议注册表。`LocalKnowledgeSearchCapabilityProvider` 负责把内部 `KnowledgeSearchResult` 裁剪成
稳定 `KnowledgeSearchHit`：只保留文档 ID、分类、标题、分数和片段。

`matchedFields`、`queryVariants`、`rerankReason` 等内部解释字段继续供检索评测使用，但不会绑死 Workflow
或远程 MCP 输出契约。

## 5. Return Apply 输出为什么再次迁移

删除 Registry 后复查发现，`return.apply` 仍通过旧名 `ReturnToolData` 跨越 Domain、Provider 和 Workflow。
这会留下第二套结果 Schema，因此本批同时完成：

- Domain 的 `ReturnApplicationResult` 只保存内部 `ReturnRecord` 和业务结果；
- Provider 仅在提交成功时映射为 `ReturnApplicationSummary`；
- 业务拒绝统一使用 `CapabilityResult.businessRejected(errorCode, message, null)`；
- Workflow 只读取 `ReturnApplicationSummary`，并支持从幂等 JSON Map 恢复；
- `success + submitted=false` 被视为不一致结果，不会伪装成业务拒绝或自动重试。

这样 Domain 不依赖 Capability API，Workflow 也不依赖领域实体。

## 6. 删除了哪些旧类型

本批物理删除了以下类别：

- 兼容 Provider：`ToolRegistryCapabilityProvider`、`LegacyCapabilityContractMapper`、
  `LegacyCapabilityOverride`；
- 旧协议：`ToolRegistry`、`ToolCall`、`ToolResult`、`ToolDefinition`、
  `ToolParameterDefinition`、`ToolHandler`；
- 仅由 Registry 使用的 Order、Product、Return、User Tools 与重复 DTO；
- `ReturnToolData`；
- 对应的 Adapter/Registry 专属测试。

`Phase9LegacyDependencyBoundaryTest` 不只检查 import 清单为空，还断言这些文件必须物理不存在。后续若有人
重新创建同名兼容类型，架构测试会直接失败。

`HandoffTools` 中两个无租户旧重载和旧格式化方法也已删除。人工工单只能调用
`createHandoffTicketForTenant(...)`，tenant、user 和 conversation 必须来自服务端执行上下文。

## 7. 外部接口与兼容策略

- `GET /api/tools` 只读取 `CapabilityCatalog`，不再有第二套 Tool Schema；
- REST 调用入口是 `/api/mcp/tools/call`；
- 正式 MCP JSON-RPC 入口是 `/api/mcp` 的 `tools/list` / `tools/call`；
- 当前不存在旧 `/api/tools/call` 执行入口，因此不需要保留兼容 Controller；
- 下划线旧名称仍可在 Gateway 边界规范化为点号 ID，用于平滑接收旧请求，但 Catalog、Provider、
  Skill allowlist 和 Trace 的权威 ID 都是点号形式。

最后一项只是输入别名，不会创建第二份 Schema、Provider 或执行链。

## 8. 测试夹具为什么也要迁移

纯 Java 测试过去常手动构造 `ToolRegistry`，即使生产已经走 Capability，也会让测试继续证明旧实现。
现在 `WorkflowTestSupport.localCapabilityGateway(...)` 显式装配与 Spring 相同的七个本地 Provider。

需要观察 Repository 副作用的测试会把同一组 Repository 显式传入；只验证 MCP 协议或 Catalog 的测试可
使用全新内存 Repository 的便捷重载。该工厂只存在于 `src/test`，不会成为生产 Service Locator。

## 9. 验收结果

执行过以下检查：

```bash
sh mvnw -q -DskipTests test-compile
sh mvnw -q -Dtest=LocalOrderCapabilityProviderTest,LocalKnowledgeSearchCapabilityProviderTest,OrderQueryWorkflowIntegrationTest,PolicyQaWorkflowIntegrationTest,Phase9WorkflowRoutingBaselineTest test
sh mvnw -q clean test
sh mvnw -q -DskipTests package
```

干净构建共 495 项测试，失败 0、错误 0、跳过 0。相比上一批 508 项少 13 项，原因是删除了 15 项只验证
旧 Registry/Adapter 的测试，同时新增了 Order 与 Knowledge 独立 Provider 契约测试；不是跳过测试。

Spring Boot JAR 构建通过。包内包含新的 Order/Knowledge Provider 与 `ReturnApplicationSummary`，不存在
已删除的 ToolManager、ToolRegistry、旧 Tool 协议和 Legacy Adapter class。

Eval 结果保持：

- Agent 链路回复质量 3/3，通过率 100%；
- Trace 覆盖 3/3，通过率 100%；
- Workflow 路由 16/16，命中率 100%；
- 知识库召回 8/8，召回率与关键词覆盖率均为 100%。

## 10. 下一步

Phase 9.4 不再修改能力执行主链路，主要完成：

1. 更新 README、架构图、开发路线图和面试手册中的旧描述；
2. 让 Eval 和所有文档不再把 Legacy 类型写成当前实现；
3. 统计包依赖、独立发布需求和构建成本，书面决定是否拆 Maven Modules。
