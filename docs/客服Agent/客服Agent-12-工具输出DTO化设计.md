# 智能客服 Agent：工具输出 DTO 化设计

> 本文档记录工具层从“结构化 Map”继续升级为“明确 DTO”的阶段成果。

## 1. 为什么要把工具输出 DTO 化

`ToolResult.data` 早期使用 `Map` 或 `List`，优点是写起来快，适合先跑通链路。

但项目继续变大后，Map 会有几个问题：

- 字段名靠字符串约定，写错编译器发现不了。
- 字段类型不明确，测试和前端都要猜。
- 面试讲工具协议时，Map 会显得边界还不够清楚。
- 后续接 MCP Server 或 Monitor 页面时，更需要稳定结构。

所以当前阶段把核心业务工具输出 DTO 化，让工具协议从“能跑通”继续走向“可维护、可评测、可展示”。

DTO 化之后，项目又继续补了 `outputSchema`：

- `outputType` 描述工具返回哪个 DTO，例如 `OrderToolData`。
- `outputSchema` 描述输出字段、字段类型、字段说明和示例。
- 前端 Tools 面板直接读取 `/api/tools` 的 `outputSchema`，不再硬编码工具返回结构。

## 2. 当前完成范围

当前已经处理以下业务工具：

| 工具 | 原来 data | 现在 data |
| --- | --- | --- |
| `order_lookup` | `Map<String, Object>` | `OrderToolData` |
| `user_orders` | `Map<String, Object>` | `UserOrdersToolData` |
| `product_search` | `Map<String, Object>` | `ProductSearchToolData` |
| `product_info` | `Map<String, Object>` | `ProductInfoToolData` |
| `return_apply` | `Map<String, Object>` | `ReturnToolData` |
| `return_query` | `Map<String, Object>` | `ReturnToolData` |
| `user_lookup` | `Map<String, Object>` | `UserToolData` |
| `handoff_create` | `Map<String, Object>` | `HandoffTicketData` |

公共商品字段抽成了：

```text
ProductItemData
```

这样商品搜索和商品详情可以复用同一套商品字段。

## 3. 为什么 JSON 字段仍然用 snake_case

Java 代码里更自然的写法是：

```java
data.getOrderId()
```

但工具协议、Trace 和前端调试面板里更常见的是：

```json
{
  "order_id": "ORD-001",
  "product_name": "蓝牙耳机 Pro Max"
}
```

所以 DTO getter 上使用了 `@JsonProperty`：

```java
@JsonProperty("order_id")
public String getOrderId() {
    return orderId;
}
```

这样可以同时满足：

- Java 内部代码可读。
- JSON 输出字段稳定。
- 后续迁移 MCP 或前端展示时不用改字段名。

## 4. 新增 DTO

| DTO | 说明 |
| --- | --- |
| `OrderToolData` | 单个订单的结构化结果 |
| `UserOrdersToolData` | 用户订单列表结果 |
| `ProductItemData` | 单个商品的公共结构 |
| `ProductSearchToolData` | 商品搜索结果 |
| `ProductInfoToolData` | 商品详情结果 |
| `ReturnToolData` | 退货申请和退货查询结果 |
| `UserToolData` | 用户信息查询结果 |
| `HandoffTicketData` | 人工转接工单结果 |

## 5. 测试覆盖

`ToolRegistryTest` 已更新：

- `order_lookup` 断言 `OrderToolData`。
- `user_orders` 断言 `UserOrdersToolData`。
- `product_search` 断言 `ProductSearchToolData`。
- `product_info` 断言 `ProductInfoToolData`。
- `return_apply` 和 `return_query` 断言 `ReturnToolData`。
- `user_lookup` 断言 `UserToolData`。
- `handoff_create` 断言 `HandoffTicketData`。
- `ToolApiControllerTest` 断言 `/api/tools` 可以返回 `outputType` 和 `outputSchema`。

这样如果后续有人改 DTO 字段或工具返回类型，测试会直接暴露问题。

## 6. 面试表达

如果面试官问：“为什么工具结果不用 Map？”

可以这样回答：

> 第一版为了快速跑通，我让工具返回结构化 Map。后来为了让工具协议更稳定，我把订单、商品、退货、用户和人工转接等业务工具输出改成明确 DTO，例如 OrderToolData、ReturnToolData、HandoffTicketData。Java 内部通过 getter 访问字段，JSON 输出仍然通过 @JsonProperty 保持 snake_case。同时 ToolDefinition 会导出 outputSchema，让前端和后续 MCP Server 都能依赖稳定的数据契约。

如果面试官问：“为什么不是一开始就全部 DTO 化？”

可以这样回答：

> 第一版项目最重要的是先验证 Agent 主链路，所以 Map 更适合快速迭代。等工具协议稳定后，我再按业务优先级把核心工具升级成 DTO。这样既保留了早期开发速度，又在后期补上了类型安全、测试可读性和协议稳定性。

## 7. 后续计划

下一步可以继续：

1. 给 DTO 增加更明确的字段注释和示例。
2. 让 outputSchema 支持更完整的嵌套结构，例如 `orders[]` 的元素 schema。
3. 将 `ToolRegistry` 迁移成独立 MCP Server，让 Agent 通过 MCP Client 调用。

当前阶段已经完成核心业务工具的 DTO 化，工具层从“结构化但弱类型”继续向“结构化且强契约”演进。
