# 客服Agent-36：退货查询 Workflow 迁移说明

## 1. 本次迁移解决什么问题

迁移前，“查询已有退货申请的进度”由 `CustomerServiceSkill` 的 Legacy 分支处理。这个做法能工作，
但随着业务继续增加，会出现三个维护问题：

1. 路由、槽位收集、工具调用和回复拼装继续堆在同一个大类中。
2. `return.lookup` 的权限规则不够清晰，容易误以为知道退货单号就可以查看详情。
3. 本地旧工具返回 Java DTO，远程 MCP 返回 JSON Map，Workflow 容易被两种实现细节污染。

本批次把退货查询拆成独立的 Skill Package、Executable Workflow 和本地 Capability Provider。
旧分支暂时保留作回滚路径，等独立流程经过更多回归和稳定期后再清理。

## 2. 新增组件

| 组件 | 职责 |
| --- | --- |
| `skills/return-query/manifest.yaml` | 声明 Skill 身份、Workflow、优先级和 Capability allowlist |
| `skills/return-query/SKILL.md` | 描述退货查询边界、身份要求和禁止承诺事项 |
| `skills/return-query/references/status-guidance.md` | 约束状态回复字段和隐私保护 |
| `workflow/returnquery/ReturnQueryWorkflow.java` | 负责路由匹配、跨轮追问、调用能力和组织回复 |
| `workflow/returnquery/ReturnQueryState.java` | 在独立命名空间保存步骤和退货单号 |
| `workflow/returnquery/ReturnQueryStep.java` | 定义等待编号、查询中、完成等状态 |
| `capability/local/LocalReturnLookupCapabilityProvider.java` | 查询退货记录并通过关联订单校验归属 |
| `capability/api/contracts/ReturnStatusSummary.java` | 本地与 MCP 共用的稳定输出契约 |

## 3. 状态机

```text
START
  -> 已有 RET-001 -> LOOKING_UP_RETURN -> COMPLETED
  -> 缺少退货单号 -> WAITING_RETURN_ID

WAITING_RETURN_ID
  -> 仍无合法编号 -> WAITING_RETURN_ID
  -> 补充 RET-001 -> LOOKING_UP_RETURN -> COMPLETED

LOOKING_UP_RETURN
  -> RETURN_NOT_FOUND          -> WAITING_RETURN_ID
  -> RETURN_OWNERSHIP_MISMATCH -> COMPLETED（只提示无权查看）
  -> 稳定契约返回成功          -> COMPLETED
  -> 技术失败或契约错误        -> FAILED

任何步骤
  -> 用户要求人工 -> HandoffAction
  -> 用户取消     -> CANCELLED
```

Router 会恢复仍处于 `WAITING_RETURN_ID` 的 Workflow。因此第二轮用户只发送 `RET-001` 时，
系统仍知道这是上一轮退货进度查询的一部分，不需要用户重复完整问题。

## 4. 为什么权限校验要关联订单

当前 `ReturnRecord` 只有以下字段：

```text
returnId、orderId、reason、status
```

它没有 `userId`，所以不能只查出退货记录后直接返回。安全调用链是：

```text
returnId
  -> ReturnRepository 查退货记录
  -> record.orderId
  -> OrderRepository 查关联订单
  -> order.userId 与 ExecutionContext.principal.userId 比较
  -> 归属一致后才读取商品并返回详情
```

如果归属不一致，Provider 返回 `RETURN_OWNERSHIP_MISMATCH`，并且 `data` 为 `null`。Workflow 只回复
“该退货单不属于当前登录用户”，不会泄露订单号、商品、申请原因和处理状态。

如果退货记录指向不存在的订单，说明数据关系已经损坏。此时返回
`RETURN_ORDER_INCONSISTENT` 技术失败，而不是伪装成“编号不存在”。这能帮助运维区分用户输入错误和
后台数据完整性问题。

## 5. 为什么使用独立 Provider

旧 `return_query` 工具只查询 `ReturnRepository`，没有关联订单做归属检查。继续通过通用
`ToolRegistryCapabilityProvider` 暴露它，会留下绕过权限校验的可能。

因此生产 Spring 装配现在采用：

```text
return.lookup -> LocalReturnLookupCapabilityProvider
```

同时 `ToolRegistryCapabilityProvider` 在生产构造器中隐藏旧 `return_query`，避免两个 Provider 重复注册
同一个 Capability。手动装配和 Legacy 测试入口仍可保留旧能力，便于阶段性回滚。

## 6. 本地与 MCP 使用同一输出契约

本地 Provider 返回 `ReturnStatusSummary`；远程 MCP 可能返回普通 JSON：

```json
{
  "return_id": "RET-001",
  "order_id": "ORD-004",
  "product_name": "Type-C 数据线",
  "reason": "数据线接口松动，充电不稳定",
  "status": "审核通过"
}
```

`McpCapabilityProvider` 会把 JSON Map 恢复为同一个 `ReturnStatusSummary`。因此 Workflow 只依赖稳定
契约，不需要判断当前运行在 local、hybrid 还是 mcp-remote 模式。

## 7. 测试证据

| 测试 | 主要验证内容 |
| --- | --- |
| `ReturnQueryWorkflowTest` | 跨轮等待、成功回复、不存在、未认证、归属拒绝和人工打断 |
| `ReturnQueryWorkflowIntegrationTest` | 真实 Agent、Router、Skill allowlist、Gateway 和 Repository 完整链路 |
| `LocalReturnLookupCapabilityProviderTest` | Capability 元数据、归属校验、稳定契约和坏关联数据 |
| `McpCapabilityProviderTest` | 远程 JSON 恢复为 `ReturnStatusSummary` |
| `ToolRegistryCapabilityProviderTest` | 生产装配不再暴露旧 `return_query` |
| `NewArchitectureBoundaryTest` | Runtime、Router、Registry、Gateway 不 import 具体退货查询 Workflow |

## 8. 回滚与下一步

本批次没有删除 `CustomerServiceSkill` 中的旧退货查询代码。若新 Workflow 在后续评测中出现问题，
可以临时停用独立 Package/Bean，让 Legacy Workflow 继续承接旧行为。

下一批迁移 `ProductAdvisoryWorkflow`。它应继续遵守同一条扩展规则：新增业务只增加 Skill Package、
Workflow、稳定 Capability 契约及 Provider/Adapter，不在 `AgentRuntime`、`WorkflowRouter` 或
`DefaultCapabilityGateway` 中增加商品业务分支。
