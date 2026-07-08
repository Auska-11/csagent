# 客服Agent-29-独立 MCP Server 设计

## 1. 为什么要做独立 MCP Server

前面项目已经有 MCP-style 工具层：

- `ToolDefinition`：描述工具名称、参数和输出结构。
- `ToolCall`：统一工具调用请求。
- `ToolResult`：统一工具返回。
- `ToolRegistry`：注册和执行工具。
- `/api/mcp/tools` 和 `/api/mcp/tools/call`：REST 风格 Adapter。

这些已经能支撑前端调试和普通 HTTP 调用，但面试里如果讲 MCP，容易被追问：

- MCP Client 怎么发现工具？
- 是否支持 `tools/list` 和 `tools/call`？
- 你的工具层和协议入口是否解耦？

所以这一版补了 `/mcp` JSON-RPC 风格入口。它还运行在同一个 Spring Boot 进程里，但协议边界已经独立，后续拆成单独服务会更顺。

## 2. 当前协议能力

入口：

```http
POST /mcp
Content-Type: application/json
```

支持三个方法：

```text
initialize
tools/list
tools/call
```

### 2.1 initialize

用于告诉客户端当前 MCP Server 的基础信息：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {}
}
```

返回：

```json
{
  "protocolVersion": "2024-11-05",
  "serverInfo": {
    "name": "smart-cs-agent-mcp-server",
    "version": "0.1.0"
  },
  "capabilities": {
    "tools": {
      "listChanged": false
    }
  }
}
```

### 2.2 tools/list

返回 `ToolRegistry` 中注册的全部工具，并把现有 `inputSchema` / `outputSchema` 暴露给外部客户端。

这一步的价值是：外部系统不需要读 Java 代码，也能知道有哪些工具、需要哪些参数、返回什么结构。

### 2.3 tools/call

调用具体工具：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "order_lookup",
    "arguments": {
      "order_id": "ORD-001"
    }
  }
}
```

返回结构保留 MCP 常见形态：

```json
{
  "content": [
    {
      "type": "text",
      "text": "订单 ORD-001 ..."
    }
  ],
  "structuredContent": {
    "order_id": "ORD-001"
  },
  "isError": false
}
```

## 3. 核心类

```text
McpJsonRpcController
McpJsonRpcRequest
McpJsonRpcResponse
ToolManager
ToolRegistry
```

`McpJsonRpcController` 只处理协议适配，不直接写业务逻辑。真正的工具执行仍然交给 `ToolManager` 和 `ToolRegistry`。

这样做的好处是：

- REST Adapter 和 JSON-RPC Adapter 可以复用同一套工具注册表。
- 新增工具时只改 `ToolRegistry`，不需要同时改多个协议入口。
- 后续拆独立 MCP Server 时，可以保留工具协议层，迁移成本更低。

## 4. 异常处理

当前第一版做了两层错误：

- JSON-RPC 方法不存在：返回 `METHOD_NOT_FOUND`。
- 工具参数缺失或工具调用失败：返回 `isError=true` 的工具结果。

例如 `tools/call` 没传 `params` 时，不会空指针，而是返回：

```json
{
  "isError": true,
  "structuredContent": {
    "errorCode": "BAD_REQUEST"
  }
}
```

## 5. 验证方式

已补充测试：

```text
McpJsonRpcControllerTest
```

覆盖：

- `initialize`
- `tools/list`
- `tools/call`
- 未知 method
- `tools/call` 缺少 params

运行：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" "-Dtest=McpJsonRpcControllerTest" test
```

## 6. 面试怎么讲

可以这样讲：

> 我没有一开始把工具服务拆成独立进程，而是先把工具注册、工具定义、工具调用和协议 Adapter 分开。项目内部通过 `ToolRegistry` 管理工具，外部同时暴露 REST Adapter 和 `/mcp` JSON-RPC Adapter。这样既能本地稳定演示，又具备后续拆成独立 MCP Server 的边界。

如果被追问“现在是不是完整 MCP”，可以诚实回答：

> 当前是 MCP Server 第一版，已经覆盖工具发现和工具调用这两个核心动作，但还没有做完整会话生命周期、鉴权和流式协议。我的设计重点是先把工具协议边界做清楚，后续独立部署时不会推翻内部工具层。

