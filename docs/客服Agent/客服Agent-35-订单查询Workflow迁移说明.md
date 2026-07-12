# 客服Agent-35：订单查询 Workflow 迁移说明

## 1. 本次迁移解决什么问题

改造前，订单规则位于 `CustomerServiceSkill.ruleRoute()` 中：

```text
CustomerServiceSkill
  -> 判断订单关键词
  -> 记录 pending_slot
  -> 发出 order_lookup / user_orders
  -> LLM 整理工具结果
```

这条链路可以工作，但订单规则、多轮状态和其他业务都堆在一个 600 多行的大类中。新增订单规则时
必须继续修改这个大类，也无法明确说明订单查询到底拥有哪组 Capability。

改造后，生产 Spring 路由优先选择独立的 `OrderQueryWorkflow`：

```text
skills/order-query                    # 指令、触发描述、能力白名单
  -> WorkflowRouter
  -> OrderQueryWorkflow               # 多轮状态与下一步 Action
  -> CapabilityActionHandler
  -> CapabilityGateway
  -> ToolRegistryCapabilityProvider   # 当前本地兼容 Provider
  -> OrderSummary / OrderListResult   # 稳定输出契约
```

Legacy `CustomerServiceSkill` 暂时保留一个版本作为回滚路径，但正常订单请求不再由它执行。

## 2. 新增文件

| 文件 | 作用 |
| --- | --- |
| `skills/order-query/manifest.yaml` | 声明 Workflow 绑定、触发描述和 Capability 白名单 |
| `skills/order-query/SKILL.md` | 描述订单查询角色、安全边界和行为规则 |
| `workflow/orderquery/OrderQueryWorkflow.java` | 订单查询状态机和回复组织 |
| `workflow/orderquery/OrderQueryState.java` | 类型安全地访问 order_query 私有状态 |
| `workflow/orderquery/OrderQueryStep.java` | 定义等待订单号、查询中、完成等步骤 |
| `capability/api/contracts/OrderSummary.java` | 单个订单稳定输出契约 |
| `capability/api/contracts/OrderListResult.java` | 当前用户订单列表稳定输出契约 |

## 3. 状态机

```text
START
  -> “查看我的订单” -> LISTING_USER_ORDERS -> COMPLETED
  -> 已有 ORD-001   -> LOOKING_UP_ORDER    -> COMPLETED
  -> 缺少订单号     -> WAITING_ORDER_ID

WAITING_ORDER_ID
  -> 仍无订单号     -> WAITING_ORDER_ID
  -> 补充 ORD-001   -> LOOKING_UP_ORDER -> COMPLETED

任何步骤
  -> 用户要求人工   -> HandoffAction
  -> 用户取消       -> CANCELLED
  -> 技术失败       -> FAILED
```

Router 会优先恢复 `WAITING_ORDER_ID`。因此第二轮用户只发送 `ORD-001` 时，不需要重新表达
“我要查订单”，也不会被错误路由到通用对话。

## 4. 身份与订单归属

订单号不是访问凭证。安全规则分为两层：

1. `OrderQueryWorkflow` 要求 `AuthenticatedPrincipal.isAuthenticated()`，未登录时不会发出订单 Capability。
2. `ToolRegistryCapabilityProvider` 比较订单所属 `userId` 与 Principal；不一致时返回
   `ORDER_OWNERSHIP_MISMATCH`，并且不返回订单结构化数据。

“我的全部订单”也不会把模型生成的 `user_id` 直接交给旧工具。Provider 会用服务端 Principal
覆盖普通参数，防止用户或模型伪造其他用户 ID。

## 5. 为什么增加稳定订单契约

本地旧工具返回 `OrderToolData`，远程 MCP 返回 JSON Map。如果 Workflow 直接识别二者，就会出现：

```text
if (local) 读取 OrderToolData
if (remote) 读取 Map
```

现在 Provider 边界统一转换为：

- `OrderSummary`
- `OrderListResult`

Workflow 只读取这两个类型，因此从 local 切换到 mcp-remote 时不需要修改订单流程。

## 6. 测试证据

| 测试 | 主要验证内容 |
| --- | --- |
| `OrderQueryWorkflowTest` | 多轮追问、列表、未认证、归属拒绝和人工打断 |
| `OrderQueryWorkflowIntegrationTest` | 真实 Agent、Router、Gateway、Repository 完整链路 |
| `ToolRegistryCapabilityProviderTest` | Principal 覆盖伪造 user_id、本地 DTO 契约转换 |
| `McpCapabilityProviderTest` | 远程 JSON 恢复为相同订单契约 |
| `NewArchitectureBoundaryTest` | Runtime、Router、Gateway 不 import 具体订单 Workflow |

## 7. 回滚方式

本批次没有删除 `CustomerServiceSkill` 中的旧订单代码。若独立 Workflow 出现问题，可以临时移除
`order-query` Package/Bean 或增加功能开关，Legacy Workflow 仍能承接旧行为。

等后续订单 Eval 和一段稳定期完成后，再删除旧订单分支与对应旧测试，避免永久维护两套逻辑。
