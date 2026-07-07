# 智能客服 Agent：MCP 风格工具调用设计

## 1. 为什么要做 MCP 风格工具层

客服 Agent 不能只靠大模型聊天，很多问题必须调用确定性的业务工具：

- 查订单
- 查商品
- 申请退货
- 查询退货进度
- 查询用户信息

早期版本中，工具调用链路是：

```text
AgentRuntime
  ↓
ToolManager.execute(capability, arguments)
  ↓
switch capability
  ↓
OrderTools / ProductTools / ReturnTools / UserTools
```

这个设计简单、适合第一版，但有几个问题：

- 工具定义散落在 `switch` 里。
- 工具参数缺失时容易产生空指针。
- 工具返回结果都是普通字符串，成功失败不好区分。
- Trace 里只能看到字符串结果，不方便做统计和评测。
- 后续如果要升级 MCP Server，缺少标准工具协议边界。

所以我们先做 MCP-style 工具层。

注意：本阶段还不是完整 MCP Server，而是先把工具调用方式设计成接近 MCP 的形态。

## 2. 当前实现目标

本阶段完成第一版工具标准化，并补充了工具参数 schema：

- 新增 `ToolDefinition`：描述工具名称、说明、参数和输入 schema。
- 新增 `ToolParameterDefinition`：描述参数名称、类型、是否必填和示例值。
- 新增 `ToolCall`：表示一次工具调用请求。
- 新增 `ToolResult`：统一工具返回结构。
- 新增 `ToolRegistry`：集中注册和执行工具。
- 保留 `ToolManager`：作为兼容旧 Runtime 的门面。
- Runtime Trace 记录工具执行是否成功、错误码和结果预览。
- 单元测试覆盖工具注册、缺参、未知工具和正常调用。

## 3. 当前工具调用链路

现在工具调用链路变成：

```text
AgentRuntime
  ↓
ToolManager
  ↓
ToolCall
  ↓
ToolRegistry
  ↓
ToolHandler
  ↓
OrderTools / ProductTools / ReturnTools / UserTools / KnowledgeTools / HandoffTools
  ↓
ToolResult
```

这里最重要的变化是：

> Runtime 不再直接关心具体工具怎么执行，工具层统一用 ToolCall 输入，用 ToolResult 输出。

同时项目新增了一个只读调试接口：

```text
GET /api/tools
```

它会返回当前注册的工具列表和每个工具的 `inputSchema`。

这个接口可以理解成 MCP `tools/list` 的本地简化版。

在后续模块化开发中，项目又新增了一层轻量 MCP Adapter：

```text
GET /api/mcp/tools
POST /api/mcp/tools/call
```

这两个接口把内部 `ToolRegistry` 暴露成更接近 MCP `tools/list` 和 `tools/call` 的边界。

注意：当前仍然不是完整独立 MCP Server，而是 Spring Boot 内部的 Adapter。
这样做是为了先把“工具协议边界”讲清楚，后续再迁移到独立 MCP Server。

## 4. 核心对象

## 4.1 `ToolDefinition`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/tools/ToolDefinition.java
```

作用：描述工具元信息。

字段：

```java
private String name;
private String description;
private Map<String, String> parameters;
private List<String> requiredParameters;
private List<ToolParameterDefinition> parameterSchemas;
```

示例：

```java
new ToolDefinition(
    "order_lookup",
    "查询指定订单的状态、商品和物流信息",
    List.of(ToolParameterDefinition.stringParam(
        "order_id",
        "订单号",
        true,
        "ORD-001"
    ))
)
```

这相当于告诉系统：

```text
我有一个工具叫 order_lookup。
它需要一个 order_id。
order_id 是 string 类型。
order_id 是必填参数。
它可以查询订单状态。
```

`ToolDefinition` 可以导出接近 MCP `tools/list` 的 `inputSchema` 和 `outputSchema`。

`inputSchema` 描述工具入参：

```json
{
  "type": "object",
  "properties": {
    "order_id": {
      "type": "string",
      "description": "订单号",
      "example": "ORD-001"
    }
  },
  "required": ["order_id"]
}
```

这样后续要做三件事会更自然：

- 给前端调试面板生成工具表单。
- 让 LLM 看到更清晰的工具参数约束。
- 迁移到真正 MCP Server 时生成 `tools/list` 响应。

`outputSchema` 描述工具出参：

```json
{
  "type": "object",
  "dto": "OrderToolData",
  "properties": {
    "order_id": {
      "type": "string",
      "description": "订单号",
      "example": "ORD-001"
    },
    "tracking_no": {
      "type": "string",
      "description": "物流单号",
      "example": "SF1234567890"
    }
  },
  "required": []
}
```

这样前端 Inspector 不需要硬编码 DTO 字段，也能直接展示工具返回结构。

## 4.2 `ToolCall`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/tools/ToolCall.java
```

作用：表示一次工具调用请求。

字段：

```java
private String name;
private Map<String, Object> arguments;
```

示例：

```java
new ToolCall("order_lookup", Map.of("order_id", "ORD-001"))
```

它和 `CapabilityAction` 很像，但职责不同：

| 对象 | 所属层 | 含义 |
| --- | --- | --- |
| `CapabilityAction` | Agent 决策层 | Skill 决定“我要调用某个能力” |
| `ToolCall` | 工具执行层 | 工具协议真正执行的请求 |

## 4.3 `ToolResult`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/tools/ToolResult.java
```

作用：统一工具返回结构。

字段：

```java
private boolean success;
private String toolName;
private String message;
private Object data;
private String errorCode;
```

成功示例：

```json
{
  "success": true,
  "toolName": "order_lookup",
  "message": "Order ORD-001 info...",
  "data": {
    "found": true,
    "order_id": "ORD-001",
    "product_name": "蓝牙耳机 Pro Max",
    "status": "已发货",
    "tracking_no": "SF1234567890"
  },
  "errorCode": null
}
```

失败示例：

```json
{
  "success": false,
  "toolName": "order_lookup",
  "message": "Missing required argument(s): order_id",
  "data": null,
  "errorCode": "MISSING_ARGUMENT"
}
```

当前 `data` 已经优先返回结构化数据。订单、商品、退货、用户和人工转接等业务工具已经从 Map 升级为明确 DTO，知识库检索返回 `KnowledgeSearchResult` 列表。

例如：

- `order_lookup.data` 返回 `OrderToolData`
- `user_orders.data` 返回 `UserOrdersToolData`
- `product_search.data` 返回 `ProductSearchToolData`
- `product_info.data` 返回 `ProductInfoToolData`
- `return_apply.data` 和 `return_query.data` 返回 `ReturnToolData`
- `user_lookup.data` 返回 `UserToolData`
- `knowledge_search.data` 是 `KnowledgeSearchResult` 列表
- `handoff_create.data` 返回 `HandoffTicketData`

工具 schema 第一版已经完成，当前 `ToolDefinition` 可以通过 `ToolParameterDefinition`、`toInputSchema()` 和 `toOutputSchema()` 暴露参数类型、必填字段、示例值和输出 DTO 结构。

## 4.4 `ToolRegistry`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/tools/ToolRegistry.java
```

作用：

- 注册工具定义。
- 注册工具执行逻辑。
- 执行前检查必填参数。
- 把异常转换成 `ToolResult.failure()`。
- 提供 `listTools()` 查看当前系统支持的工具。

当前已注册工具：

| 工具名 | 说明 |
| --- | --- |
| `order_lookup` | 查询订单状态 |
| `user_orders` | 查询用户全部订单 |
| `product_search` | 搜索商品 |
| `product_info` | 查询商品详情 |
| `return_apply` | 提交退货申请 |
| `return_query` | 查询退货进度 |
| `user_lookup` | 查询用户信息 |
| `knowledge_search` | 检索客服知识库 |
| `handoff_create` | 创建人工转接工单 |

## 4.5 `ToolManager`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/tools/ToolManager.java
```

当前定位：

```text
兼容旧 Runtime 的工具门面
```

也就是说，Runtime 仍然可以这样调用：

```java
toolManager.execute(capability, arguments);
```

但内部已经变成：

```java
new ToolCall(capability, arguments)
toolRegistry.execute(toolCall)
```

这样做可以减少一次性改动，让架构平滑演进。

## 5. Trace 中的变化

之前 `tool_observed` 只能记录：

```json
{
  "resultPreview": "Order ORD-001 info..."
}
```

现在可以记录：

```json
{
  "capability": "order_lookup",
  "success": true,
  "errorCode": null,
  "messagePreview": "Order ORD-001 info..."
}
```

这对后续评测很重要。

例如评测系统可以直接判断：

- 工具是否成功：`success`
- 失败原因是什么：`errorCode`
- 是否调用了期望工具：`capability`

## 6. 和真正 MCP 的关系

本阶段是 MCP-style，不是完整 MCP Server。

可以这样理解：

```text
现在：
AgentRuntime -> ToolManager -> ToolRegistry -> Java Tools

后续真正 MCP：
AgentRuntime -> MCP Client -> MCP Server -> Java Tools
```

我们现在做的对象可以对应到 MCP 思想：

| 当前对象 | MCP 思想中的角色 |
| --- | --- |
| `ToolDefinition` | tools/list 中的工具描述 |
| `ToolCall` | tools/call 请求 |
| `ToolResult` | tools/call 响应 |
| `ToolRegistry` | MCP Server 内部工具注册表 |
| `ToolManager` | 未来可替换成 MCP Client |

所以这个阶段不是绕远路，而是在为真正 MCP 做铺垫。

## 7. 为什么不直接上完整 MCP Server

原因很简单：当前项目还处在学习和简历项目阶段，最重要的是先把核心链路做扎实。

如果现在直接拆 MCP Server，会引入：

- 进程间通信
- 服务启动顺序
- 协议 SDK
- 网络错误处理
- 本地调试复杂度

这些不是不能做，但会让学习成本一下子变高。

当前策略更稳：

1. 先把工具调用协议标准化。
2. 再把工具层从进程内调用迁移到 MCP Server。
3. 最后在简历和面试里讲清楚演进路径。

## 8. 测试设计

新增测试：

```text
src/test/java/com/smartcs/smart_cs_agent/tools/ToolRegistryTest.java
src/test/java/com/smartcs/smart_cs_agent/controller/McpToolControllerTest.java
```

覆盖：

- 正常调用 `order_lookup`
- 缺少 `order_id` 返回 `MISSING_ARGUMENT`
- 调用未知工具返回 `TOOL_NOT_FOUND`
- `listTools()` 能列出已注册工具
- `ToolDefinition` 能暴露参数 schema、`inputSchema` 和 `outputSchema`
- `/api/tools` 能返回工具列表、`inputSchema` 和 `outputSchema`
- `/api/mcp/tools` 能返回 MCP Adapter 工具列表
- `/api/mcp/tools/call` 能按工具名称和参数调用真实工具
- 正常调用 `knowledge_search`
- 正常调用 `handoff_create`
- 订单、商品、退货、用户和人工转接工具返回明确 DTO

这样可以证明工具层不是只有概念，而是真的具备统一注册、统一校验、统一返回能力。

## 9. 面试表达

如果面试官问：“你这个工具调用和 MCP 有什么关系？”

可以这样回答：

> 当前项目第一版没有直接上完整 MCP Server，而是先参考 MCP 的思想重构工具层。我把工具调用拆成 ToolDefinition、ToolParameterDefinition、ToolCall、ToolResult 和 ToolRegistry。ToolDefinition 描述工具元信息并能导出 inputSchema 和 outputSchema，ToolCall 表示一次工具请求，ToolResult 统一成功失败和错误码，ToolRegistry 负责工具注册、参数校验和执行。后续又补了一层 MCP Adapter，提供 `/api/mcp/tools` 和 `/api/mcp/tools/call`，让外部可以通过更接近 MCP tools/list、tools/call 的方式发现和调用工具。这样 Runtime 不再依赖具体工具实现，前端也能直接展示工具入参和出参协议。后续如果要迁移成 MCP Server，可以把 ToolRegistry 放到独立工具服务里，把 ToolManager 替换成 MCP Client。

如果面试官问：“为什么要统一 ToolResult？”

可以这样回答：

> 因为客服 Agent 里工具失败是常态，比如订单号缺失、退货单号不存在、用户身份无效。如果工具只返回字符串，Agent 很难判断成功失败，也不好做 Trace 和评测。我用 ToolResult 统一了 success、toolName、message、data、errorCode，这样 Runtime、Trace、评测系统都可以基于结构化结果做判断。

## 10. 后续扩展

下一步可以继续做：

1. 增加工具调用失败后的重试或降级。
2. 给 outputSchema 增加更完整的嵌套对象描述，例如 `orders[]`、`products[]` 的元素结构。
3. 暴露更完整的调试接口查看工具调用示例。
4. 把 `ToolRegistry` 抽到独立模块，改造成真正 MCP Server。

## 11. 当前结论

MCP-style 工具调用第一版已经完成，MCP Adapter 第一版也已经完成。

当前项目已经具备：

- 工具可定义
- 工具可注册
- 工具可发现
- 参数可校验
- 参数 schema 可导出
- 输出 schema 可导出
- MCP Adapter 可发现和调用工具
- 结果可结构化
- Trace 可记录成功失败
- 知识库检索也已纳入统一工具协议
- 人工转接工单也已纳入统一工具协议
- 订单、商品、退货、用户和人工转接等业务工具已返回明确 DTO
- 前端 Tools 面板可以展示工具 inputSchema 和 outputSchema
- 知识库工具返回 `KnowledgeSearchResult` 列表，仍然保持结构化结果

这一步完成后，工具调用层从“能用的 switch”升级成了“可扩展的工具协议雏形”。
