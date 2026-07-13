# 客服 Agent Phase 9 最终验收与架构改造完成说明

> 验收日期：2026-07-14
> 验收结论：Phase 9.1 至 9.5 全部完成，架构改造目标关闭
> 发布范围：仅本地提交，不推送远端

## 1. 这次验收证明什么

Phase 9 的目标不是增加新的客服功能，而是删除迁移期保留的第二套执行入口，并证明删除后真实应用仍然
可以完成客服任务、访问 MySQL、暴露 MCP 能力和正确处理依赖故障。

最终生产主链路只有一条：

```text
HTTP + JWT Principal
  -> CustomerServiceAgent
  -> AgentRuntime
  -> WorkflowRouter -> AgentWorkflow
  -> ActionHandler
  -> CapabilityGateway -> CapabilityProvider
```

其中三种容易混淆的概念已经明确分工：

- Agent Skill Package：保存指令、references、版本和 Capability allowlist，不执行 Java 业务流程。
- Workflow：负责路由后的任务决策、状态迁移、追问、确认和最终回复。
- MCP：负责远程发现和传输 Capability，不承担 Workflow 决策。

这意味着新增业务流程时，不需要把逻辑继续堆进 `AgentRuntime`，也不需要恢复已经删除的
`CustomerServiceSkill`、`ToolManager` 或 `ToolRegistry`。

## 2. Phase 9 完成结果

| 子阶段 | 完成结果 |
| --- | --- |
| 9.1 | 冻结路由与 Legacy 依赖基线，增加只允许收缩的架构门禁 |
| 9.2 | 用 `GeneralCustomerServiceWorkflow` 替换 Legacy Skill/Adapter，稳定 ID 仍为 `customer_service` |
| 9.3 | Handler/API 直接依赖 `CapabilityGateway`，删除旧 Tool 主链路和重复 Schema |
| 9.4 | 7 个独立 Local Provider 覆盖 9 个 Capability；基于发布边界决定暂不拆 Maven Modules |
| 9.5 | 默认测试、Eval、真实 MySQL、JAR、Docker/Compose、业务链路与故障恢复全部通过 |

保留 `customer_service` 这个名字不是保留旧实现。它是 Skill manifest 和已持久化会话可见的稳定
Workflow ID；Phase 9 只替换其 Java 实现，避免清理兼容代码时破坏数据契约。

## 3. 自动化回归结果

### 3.1 默认测试与 Eval

干净构建的默认测试结果：

```text
tests=499
failures=0
errors=0
skipped=0
```

关键 Eval 结果：

- Workflow 路由：16/16。
- 知识库召回：8/8。
- Agent 回复、Capability Trace 与状态终止：3/3。

这些评测不是为了证明模型在所有输入上都正确，而是冻结 Phase 9 删除前后的确定性业务行为，防止清理
兼容层时把请求重新路由到通用兜底，或漏掉 Capability 调用。

### 3.2 架构门禁

`Phase9LegacyDependencyBoundaryTest` 同时检查源码依赖和文件物理存在性。下面这些生产入口已删除，且
不能被静默加回：

- `CustomerServiceSkill`
- `LegacySkillWorkflowAdapter`
- `ToolManager`
- `ToolRegistry`
- `ToolRegistryCapabilityProvider`
- 旧 `ToolCall`、`ToolResult`、`ToolDefinition`、`ToolParameterDefinition` 和重复 DTO/Schema Mapper

`Phase9PackageBoundaryTest` 则保护单 Module 内的依赖方向。当前先用自动化边界解决职责耦合，不为了目录
形式立即拆多 Module。

## 4. 真实 MySQL 与 JAR 验收

### 4.1 真实 MySQL

只启动 Compose MySQL 后执行：

```bash
sh mvnw -q -Dtest=RealMySqlPersistenceIT test
```

测试通过，说明 JDBC 实现不是只在 Mock 或 H2 假设下工作。该测试覆盖真实 MySQL 方言、表结构和核心
持久化契约。

### 4.2 Spring Boot JAR

JAR 构建与独立启动通过。以 memory 模式监听 `18081` 时：

- liveness 返回 HTTP 200。
- readiness 返回 HTTP 200。
- JAR 内包含 9 个 `skills/*/manifest.yaml`。
- JAR 内不包含本阶段删除的 Legacy Skill/Tool class。

JAR 验收很重要，因为 IDE 或 Maven 测试类路径可以读取仓库文件，而正式运行只看打包后的资源。检查
JAR 可以避免“源码有 Skill，镜像里却没有”的假通过。

## 5. Docker 与 Compose 验收

### 5.1 镜像和容器制品

Agent 镜像构建成功：

```text
image=smart-cs-agent:latest
image_id=sha256:1f4be1fb39f00634791d5b7799f621dfcfd2bbae98c48cc3b323e28f930e408b
```

从正在运行的容器复制 `/app/app.jar` 后再次检查，结果为：

```text
jar_sha256=86fc0f9d78e80f99cdc008a576a50d6dc89061b640512bba727586bc12d7bfd3
skill_manifest_count=9
legacy_class_count=0
```

这里检查容器内的实际 JAR，而不是复用宿主机 `target/`，因此可以同时验证 Dockerfile 的 COPY 和
Spring Boot 重打包结果。

### 5.2 完整 Compose 健康状态

完整 Compose 使用宿主机 `18080` 端口启动。Agent、MySQL 均进入 healthy，Qdrant 正常运行；Agent 的
JDBC readiness 返回：

```json
{
  "status": "UP",
  "components": {
    "persistence": {"status": "UP"},
    "readinessState": {"status": "UP"}
  }
}
```

`persistence=UP` 证明这不是只检查 JVM 是否启动，而是实际检查了核心数据库依赖。

## 6. 关键业务链路验收

Demo JWT 签发接口返回 HTTP 200，并带 `Cache-Control: no-store`。验收过程中令牌只保存在临时文件和
Shell 变量中，没有写入项目文档或 Git。

### 6.1 订单查询

输入：

```text
帮我查一下订单 ORD-001
```

结果返回商品、已发货状态和物流单号。Trace 的关键路径为：

```text
order_query
  -> order.lookup
  -> providerId=local-order
  -> outcome=SUCCESS
  -> workflowStatus=COMPLETED
```

这证明订单请求没有回到通用客服或已删除的 Tool 兼容链。

### 6.2 政策 RAG

输入：

```text
七天无理由退货政策是什么？
```

结果引用本地《退换货政策》，Trace 的关键路径为：

```text
policy_qa
  -> knowledge.search
  -> providerId=local-knowledge-search
  -> outcome=SUCCESS
  -> workflowStatus=COMPLETED
```

### 6.3 REST Catalog 与 MCP Catalog

`GET /api/tools` 和 MCP JSON-RPC `tools/list` 均返回同一组 9 个 canonical Capability：

```text
handoff.request
product.search
product.lookup
user.lookup
return.apply
return.lookup
knowledge.search
order.lookup
order.list-by-user
```

两种协议入口共享 `CapabilityDefinition` 权威 Catalog，没有各自维护一套 Tool Schema。

## 7. MySQL 掉线与恢复

停止 MySQL 后，第一次探测即得到：

```text
liveness=200
readiness=503
persistence=DOWN
```

这符合健康探针语义：JVM 仍然活着，不应该被当成进程崩溃；但核心持久化不可用，实例不能继续接收
新流量。

重新启动 MySQL 后：

```text
mysql=healthy
readiness=200
persistence=UP
```

恢复后再次读取故障前的 `phase9-order-final` 会话返回 HTTP 200，Workflow 状态仍为 `COMPLETED`，证明
恢复不是创建一份新的内存会话。

## 8. 容器清理结果

验收结束后执行：

```bash
SMARTCS_AGENT_PORT=18080 docker compose down
```

Agent、MySQL、Qdrant 容器和 Compose 网络均已停止并移除，`docker compose ps --all` 为空。命令没有使用
`-v`，因此 MySQL/Qdrant 数据卷仍保留，后续可以继续复现实验。

## 9. 为什么当前不拆 Maven Modules

当前系统只有一个 JAR、一个 Agent 镜像和一个发布生命周期。此时强行拆 Module 会增加 POM、测试夹具和
构建维护成本，却不会带来独立部署收益；同时顶层 `agent`、`workflow` 和共享上下文仍存在需要继续治理
的双向依赖。

因此 Phase 9 的结论是：

1. 当前保持单 Maven Module。
2. 先用 package 边界、SPI、Registry 和架构测试固定依赖方向。
3. MCP Server 需要独立部署、Agent Core 被第二个应用复用，或团队出现独立发布所有权时，再先抽
   contracts，随后拆分 core、adapters、MCP 和 application。

这是“暂不拆”，不是“永远不拆”。详细依赖数据见
[`客服Agent-72-Phase9MavenModules决策与包边界说明.md`](客服Agent-72-Phase9MavenModules决策与包边界说明.md)。

## 10. 架构改造完成，但项目并非生产终局

本轮目标已经完成：新增业务 Skill 不需要修改 `AgentRuntime`、核心 Router 或 `CapabilityGateway`；旧双轨
执行链已经删除；9 个能力有单一 Catalog；测试、真实数据库和容器运行均留下证据。

仍需诚实保留的生产化不足：

1. 需要两个真实 Agent 进程验证 Outbox 租约接管和管理员竞态。
2. JDBC 仍通过 `DriverManager` 建连，尚未引入 HikariCP 和 Flyway/Liquibase。
3. 远程 MCP Catalog 不能在运行中原子替换，也没有自动 Provider 故障转移。
4. 生产身份仍需迁移到独立 IdP 和非对称密钥，并建设集中式安全审计。
5. 外部售后平台缺少按租户与 Idempotency-Key 查询结果的只读契约，自动 reconcile 仍不可用。
6. CI 尚未加入镜像扫描、SBOM、签名和 Compose smoke test。

这些是后续生产化目标，不影响 Phase 9“删除兼容层并证明唯一主链路”的完成结论。

## 11. 推荐阅读顺序

初次阅读代码时建议按以下顺序：

1. [`客服Agent-01-架构设计.md`](客服Agent-01-架构设计.md)：先理解当前主链路。
2. [`客服Agent-68-Phase9兼容层清理实施计划与基线审计.md`](客服Agent-68-Phase9兼容层清理实施计划与基线审计.md)：理解为什么不能直接删除旧类。
3. [`客服Agent-69-Phase9通用客服Workflow与LegacySkill清理说明.md`](客服Agent-69-Phase9通用客服Workflow与LegacySkill清理说明.md)：理解稳定 Workflow ID 与实现替换。
4. [`客服Agent-70-Phase9Capability主链路与ToolManager删除说明.md`](客服Agent-70-Phase9Capability主链路与ToolManager删除说明.md)：理解 Handler 如何直接调用 Gateway。
5. [`客服Agent-71-Phase9独立CapabilityProvider与ToolRegistry清理说明.md`](客服Agent-71-Phase9独立CapabilityProvider与ToolRegistry清理说明.md)：理解独立 Provider 和单一 Schema。
6. 本文：核对最终运行证据与仍未完成的边界。
