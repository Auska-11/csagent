# 客服Agent-32-售后工单平台联调设计

## 1. 为什么做后端联调

客服 Agent 项目负责理解用户问题、调用工具、生成回复；但真实业务里，复杂售后问题最终通常要进入工单系统：

```text
Agent 识别用户需要人工
  -> 生成问题摘要
  -> 创建售后工单
  -> 后端平台接管 SLA、派单、状态流转、通知和统计
```

桌面 `demo/after-sales-sla-platform` 已经提供了完整的工单后端：

- `POST /api/tickets`
- 工单幂等创建
- SLA 规则和计时
- 派单、状态流转、日志、统计
- `X-Tenant-Id`、`X-User-Id`、`X-User-Role` 请求头认证兼容

所以第一版联调放在 `smart-cs-agent` 侧完成：Agent 调用已有后端接口，不改后端项目。

## 2. 当前调用链

```text
用户：我要投诉，转人工
  -> CustomerServiceSkill 判断需要 HandoffAction
  -> AgentRuntime 执行 HandoffAction
  -> ToolManager 调用 handoff_create
  -> HandoffTools
  -> HttpAfterSalesTicketClient
  -> POST http://localhost:8081/api/tickets
  -> 返回 AS-{id}
```

## 3. 双模式设计

配置：

```yaml
smartcs:
  handoff:
    mode: ${SMARTCS_HANDOFF_MODE:memory}
    after-sales:
      base-url: ${SMARTCS_AFTER_SALES_BASE_URL:http://localhost:8081}
      tenant-id: ${SMARTCS_AFTER_SALES_TENANT_ID:demo}
      agent-user-id: ${SMARTCS_AFTER_SALES_AGENT_USER_ID:agent-service}
      agent-user-role: ${SMARTCS_AFTER_SALES_AGENT_USER_ROLE:AGENT}
```

两种模式：

| 模式 | 说明 |
| --- | --- |
| `memory` | 默认模式，生成 `HD-xxxx` 模拟工单，不依赖后端平台 |
| `after-sales-http` | 联调模式，调用售后工单平台创建真实工单，返回 `AS-{id}` |

默认保留 `memory` 是为了保证：

- 不启动 `demo` 后端也能运行 Agent。
- 单元测试和 CI 不依赖外部服务。
- 面试时可以先演示 Agent 单项目，再演示双项目联动。

## 4. 字段映射

Agent 侧字段到工单平台字段：

| Agent 字段 | 售后平台字段 | 说明 |
| --- | --- | --- |
| `userId` | `customerId` | 用户 ID 作为客户标识 |
| `conversationId` | `Idempotency-Key` 的一部分 | 防止重复建单 |
| `reason` | `issueType` 推断依据 | 投诉、纠纷、退货、物流等 |
| `summary` | `description` | 给人工客服看的摘要 |
| `reason + summary` | `priority` 推断依据 | 投诉/赔偿/维权为 `URGENT` |
| `reason + summary` | `productLine` 推断依据 | 蓝牙、退货、订单、发票等 |

幂等键：

```text
agent:{conversationId}:handoff:{reason}
```

例如：

```text
agent:conv-001:handoff:COMPLAINT
```

## 5. HTTP 请求

请求头：

```http
X-Tenant-Id: demo
X-User-Id: agent-service
X-User-Role: AGENT
Idempotency-Key: agent:conv-001:handoff:COMPLAINT
```

请求体：

```json
{
  "title": "Agent 转人工：用户投诉蓝牙耳机坏了，希望尽快赔偿",
  "description": "用户ID：USER-001\n会话ID：conv-001\n转人工原因：COMPLAINT\nAgent 摘要：用户投诉蓝牙耳机坏了，希望尽快赔偿",
  "customerId": "USER-001",
  "productLine": "bluetooth",
  "issueType": "complaint",
  "priority": "URGENT"
}
```

成功返回后，Agent 工具数据中会把工单号保存为：

```text
AS-123
```

## 6. 失败兜底

如果售后平台不可用、网络异常或接口返回失败，Agent 不直接把异常暴露给用户，而是生成本地待补建工单：

```text
ticketId: HD-xxxx
status: REMOTE_HANDOFF_PENDING
```

这样做的原因：

- 用户体验优先，不能因为后端短暂不可用就让聊天接口 500。
- 真实生产里可以把 pending 请求写入 MQ 或数据库，后续异步补建。
- 幂等键保证重试不会重复创建工单。

## 7. 本地联调步骤

先启动售后工单平台：

```powershell
cd C:\Users\feasycom\Desktop\demo\after-sales-sla-platform
mvn -s .mvn\settings.xml spring-boot:run
```

再启动 Agent 项目：

```powershell
cd C:\Users\feasycom\Desktop\smart-cs-agent
$env:SMARTCS_HANDOFF_MODE="after-sales-http"
$env:SMARTCS_AFTER_SALES_BASE_URL="http://localhost:8081"
.\mvnw.cmd spring-boot:run
```

发送聊天：

```text
我要投诉，帮我转人工
```

预期：

- Agent 回复中出现 `AS-{id}`。
- Agent Trace 中出现 `handoff_requested` 和 `handoff_created`。
- 售后工单平台前端 `http://localhost:8081/` 可以看到新工单。

## 8. 验证方式

新增测试：

```text
HandoffToolsTest
HttpAfterSalesTicketClientTest
```

覆盖：

- 默认 memory 模式仍生成 `HD-` 工单。
- `after-sales-http` 模式能正确映射标题、描述、客户、产品线、问题类型、优先级和幂等键。
- HTTP 客户端会带上 demo 后端要求的请求头。
- 售后平台失败时返回 `REMOTE_HANDOFF_PENDING`。

运行：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" "-Dtest=HandoffToolsTest,HttpAfterSalesTicketClientTest" test
```

## 9. 面试怎么讲

可以这样讲：

> 我把 Agent 项目和传统售后工单系统做成上下游关系。Agent 负责理解用户诉求、判断是否需要人工、生成问题摘要；工单平台负责 SLA、派单、状态机、日志和统计。联调时，Agent 通过 `HandoffTools` 调用售后平台 `POST /api/tickets`，并用 `Idempotency-Key` 保证重试不重复建单。

继续补充设计取舍：

> 我保留了 `memory` 和 `after-sales-http` 两种模式。默认 memory 保证 Agent 单项目可运行和测试稳定；联调时打开 HTTP 模式即可创建真实售后工单。如果后端不可用，Agent 会生成 `REMOTE_HANDOFF_PENDING` 本地工单，后续可以接 MQ 异步补建。

