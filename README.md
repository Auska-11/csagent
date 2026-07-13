# Smart CS Agent

Smart CS Agent 是一个面向电商客服场景的 Java Agent 项目，基于 Spring Boot 和 LangChain4j 构建。

这个项目不是简单的“大模型聊天 Demo”，而是把客服 Agent 拆成可解释、可测试、可扩展的任务执行系统：Agent 先判断用户意图，再根据需要追问信息、调用业务工具、检索知识库、生成客服回复，并把每一步执行过程记录到 Trace。

## 当前能力

- 订单查询：支持指定订单查询、查询当前用户全部订单、缺少订单号时主动追问。
- 商品咨询：支持商品搜索、价格和库存咨询。
- 退换货：支持退货进度查询，以及“我要退货 -> 追问订单号 -> 追问原因 -> 提交退货申请”的多轮流程。
- 多轮记忆：支持短期消息窗口、任务记忆快照、规则摘要压缩；摘要默认限制为 2000 个 Unicode 字符，生成和旧快照恢复时都会脱敏，超限后优先保留最近内容。Trace 会记录 `memory_loaded` / `memory_compressed`，前端 Memory 面板可查看当前会话记忆。
- 长期用户偏好：支持记录“我喜欢蓝牙耳机”等商品偏好，默认 30 天 TTL，重复表达会刷新有效期；内存实现限制用户总量、每用户偏好数和单条长度，并按用户访问顺序、偏好最后表达顺序淘汰旧数据。手机号、邮箱、证件号、长数字和常见凭据会在写入前脱敏，敏感信息-only 输入不会保存；同时支持查询和清空长期记忆。
- 知识库问答：支持退货政策、发票、保修、配送、转人工规则等 Markdown 知识库检索，并已增强查询改写、多路召回、本地轻量向量分、简单 rerank 和 `knowledge_miss` Trace；可选开启真 embedding + Qdrant 向量数据库模式。
- Multi-Agent 路由：支持把用户消息路由到售前、售后、投诉和通用专家域，并在 Trace 中记录 `agent_routed`。
- 人工转接：支持用户主动要求人工、投诉、赔偿或维权类表达；默认创建模拟人工工单，开启联调模式后可调用售后工单平台创建真实工单。
- Agent Trace：接口和前端调试面板可以看到 Action、工具调用、LLM 调用、转人工、耗时等事件。
- 前端调试工作台：支持多用户会话、典型 Demo 脚本、Trace 过滤/复制、Trace 指标摘要，以及工具 input/output schema 可视化。
- 评测报告看板：前端 Eval 面板可以展示最新评测概览、历史趋势和核心指标变化。
- LLM 降级：模型调用失败时不会直接中断接口，会基于工具结构化结果生成模板回复，并记录 `llm_failed` Trace。
- Capability 与 MCP：统一 `CapabilityDefinition`、输入/输出 schema、三态结果、Gateway 和 Local/MCP Provider；`GET /api/mcp/tools`、`POST /api/mcp/tools/call` 与 `POST /mcp` 共用同一 Catalog 和 Server Adapter。
- 独立 MCP Server 第一版：`POST /mcp` 支持 `initialize`、`tools/list` 和 `tools/call`；HTTP Client 每次请求使用 MCP_CLIENT Bearer JWT，JSON-RPC body 不再携带身份凭据。
- 数据库持久化：支持 `memory` / `jdbc` 双模式，默认内存模式保证本地测试和 CI 稳定；JDBC 模式除业务数据外，还持久化会话状态和 Capability 幂等记录，并为连接、网络读取和 SQL 执行设置明确超时。
- 状态演进：Workflow 私有状态支持按 Workflow ID 注册 `1 -> 2 -> 3` 逐版本迁移链；缺链、重复注册和未来版本都会失败关闭，不在 Runtime、Router 或 Codec 中堆业务分支。
- 后端联调：`HandoffTools` 支持 `memory` / `after-sales-http` 双模式，可调用桌面 `demo/after-sales-sla-platform` 的 `POST /api/tickets` 创建真实售后工单。
- Monitor 指标与反馈闭环：支持 `GET /api/monitor/summary`、`POST /api/monitor/feedback`、`GET /api/monitor/feedback/candidates`、候选样本审核接口、Eval case 草稿导出接口和半自动合并正式评测集接口，基于 Trace 聚合整体/按工具成功率、LLM 失败率、转人工次数、平均耗时、用户反馈有用率、候选样本审核状态数量和负反馈问题类型分布，并能把无用反馈沉淀成 Eval 待审核候选样本；候选样本会持久化到本地 JSON 文件，服务重启后仍可继续审核；Monitor Summary 会进一步生成结构化风险告警，前端 Monitor 面板可以直接看到工具失败率、LLM 失败率、低有用反馈率、不安全承诺和内部实现泄露等风险。
- 接口工程化：聊天接口使用明确 DTO、Spring Validation 和统一 `ApiErrorResponse`。
- 统一认证与 RBAC：Spring Security Resource Server 校验 Bearer JWT 的 HS256 签名、issuer、audience、时间、subject 和 tenant；Chat/Memory、反馈、管理面板与 MCP 使用 CUSTOMER、ADMIN、MCP_CLIENT 角色隔离，未知地址默认拒绝。
- 部署工程化：支持 Docker 多阶段构建、`docker compose` 一键启动、环境变量注入，并提供独立 liveness/readiness 健康探针。
- 离线评测：覆盖路由、知识库召回、回复质量规则、Agent 链路回复、LLM-as-Judge 五类评测，并输出 JSON 总览、历史快照和趋势指标。

## 架构概览

```text
前端页面
  -> Spring Security Resource Server
  -> JwtPrincipalConverter -> AuthenticatedPrincipal
  -> ChatApiController -> ChatRequest
  -> CustomerServiceAgent
  -> ConversationStore / SessionStore / TurnContext
  -> AgentRuntime
  -> WorkflowRouter -> WorkflowRegistry -> 独立业务 Workflow
  -> ActionHandlerRegistry -> AgentAction Handler
  -> CapabilityGateway
       -> Local Capability Provider -> Domain Service -> Repository
       -> MCP Capability Provider -> HTTP JSON-RPC -> Remote MCP Server

AgentSkillPackageRegistry
  -> manifest.yaml / SKILL.md / prompts / references
```

核心设计取舍：

- Agent Skill Package 表达说明、Prompt、references 和能力 allowlist；Executable Workflow 负责任务状态机。
- `AgentRuntime` 只执行通用循环，Action Handler 执行动作，Capability Provider 提供原子业务能力。
- 高频明确场景优先用规则，表达模糊时再用 LLM 兜底。
- 当前输入、跨轮状态、本轮执行过程分别放在 `ChatRequest`、`ConversationState`、`TurnContext`。
- Local/MCP Provider 共用 Capability 契约，Workflow 不感知能力位于本地还是远程。
- HTTP 身份只来自已验证 JWT Principal，不能由请求体、LLM 参数或 MCP `_meta` 覆盖。
- Repository 默认使用内存模式，开启 JDBC 模式后可连接 MySQL，保证学习演示和真实持久化两种需求都能覆盖。
- 人工转接默认使用本地工单，联调时再切换到售后工单平台，保证单项目运行和双项目联动都稳定。
- RAG 默认使用本地检索，开启 `embedding-qdrant` 后再依赖真实 embedding API 和 Qdrant，保证基础演示和高级检索都能覆盖。
- 先做稳定、可解释的规则评测，再补 LLM-as-Judge。

## 主要接口

本地工作台先从 Demo-only 入口取得预置身份的短期 JWT：

```http
POST /api/auth/demo/tokens
```

响应带 `Cache-Control: no-store`。生产必须设置 `SMARTCS_SECURITY_DEMO_ISSUER_ENABLED=false`，并由真实
身份系统签发 JWT。除首页、Demo 签发入口和健康探针外，下面的请求都需要：

```http
Authorization: Bearer <JWT>
```

主要角色边界：Chat/Memory 和反馈允许 CUSTOMER 或 ADMIN；Monitor 其余接口、Eval、Tool 只允许
ADMIN；`/api/mcp/**` 与 `/mcp` 允许 MCP_CLIENT 或 ADMIN。

发送聊天消息：

```http
POST /api/chat/send
Authorization: Bearer <CUSTOMER_JWT>
Content-Type: application/json

{
  "message": "我要退货",
  "conversationId": "demo-001"
}
```

空消息会返回结构化错误：

```json
{
  "errorCode": "VALIDATION_ERROR",
  "message": "Message cannot be empty",
  "timestamp": 1783350000000
}
```

接口层还提供了全局异常处理，JSON 解析失败、参数异常和未预期异常都会统一包装成 `ApiErrorResponse`。

查看当前会话记忆：

```http
GET /api/chat/conversations/{conversationId}/memory
Authorization: Bearer <CUSTOMER_JWT>
```

查看用户长期记忆：

```http
GET /api/chat/users/{userId}/memory
Authorization: Bearer <CUSTOMER_JWT>
```

清空当前会话记忆：

```http
DELETE /api/chat/conversations/{conversationId}/memory
```

清空用户长期记忆：

```http
DELETE /api/chat/users/{userId}/memory
```

查看已注册工具和输入/输出 schema：

```http
GET /api/tools
Authorization: Bearer <ADMIN_JWT>
```

查看 MCP Adapter 工具列表：

```http
GET /api/mcp/tools
Authorization: Bearer <MCP_CLIENT_JWT>
```

通过 MCP Adapter 调用工具：

```http
POST /api/mcp/tools/call
Authorization: Bearer <MCP_CLIENT_JWT>
Content-Type: application/json

{
  "name": "order.lookup",
  "arguments": {
    "order_id": "ORD-001"
  }
}
```

通过 JSON-RPC 风格 MCP Server 调用工具：

```http
POST /mcp
Authorization: Bearer <MCP_CLIENT_JWT>
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "order.lookup",
    "arguments": {
      "order_id": "ORD-001"
    }
  }
}
```

查看 Monitor 在线指标：

```http
GET /api/monitor/summary
Authorization: Bearer <ADMIN_JWT>
```

提交用户反馈：

```http
POST /api/monitor/feedback
Authorization: Bearer <CUSTOMER_JWT>
Content-Type: application/json

{
  "conversationId": "conv-USER-001-001",
  "rating": "HELPFUL",
  "reason": "前端快捷反馈：有用",
  "assistantReply": "您的订单已经发货"
}
```

查看由无用反馈生成的 Eval 候选样本：

```http
GET /api/monitor/feedback/candidates
```

审核 Eval 候选样本：

```http
POST /api/monitor/feedback/candidates/{candidateId}/review
Content-Type: application/json

{
  "status": "APPROVED",
  "reviewNote": "信息完整，可以进入回复质量评测"
}
```

导出审核通过的 Eval case 草稿：

```http
GET /api/monitor/feedback/candidates/export
```

人工补齐断言后，半自动合并到正式回复质量评测文件：

```http
POST /api/monitor/feedback/candidates/merge
Content-Type: application/json

{
  "cases": [
    {
      "sourceCandidateId": "EVAL-CAND-000001",
      "id": "feedback_eval_cand_000001",
      "candidateReply": "可以为您申请退货",
      "requiredKeywords": ["退货时效"],
      "forbiddenKeywords": ["Tool"],
      "expectedPass": false
    }
  ]
}
```

Eval 候选样本默认保存到本地文件：

```text
data/feedback-eval-candidates.json
```

这个路径可以通过环境变量覆盖：

```powershell
$env:SMARTCS_MONITOR_EVAL_CANDIDATES_FILE="data/feedback-eval-candidates.json"
```

`data/` 目录已经加入 `.gitignore`，避免把本地演示数据提交到仓库。

正式回复质量评测文件默认写入：

```text
src/test/resources/eval/response_quality_eval_cases.json
```

这个路径可以通过环境变量覆盖：

```powershell
$env:SMARTCS_MONITOR_RESPONSE_QUALITY_EVAL_FILE="src/test/resources/eval/response_quality_eval_cases.json"
```

查看最新评测报告和趋势：

```http
GET /api/eval/reports
```

查看服务健康状态：

```http
GET /api/health                       # 旧版兼容 liveness
GET /actuator/health/liveness         # 进程是否存活
GET /actuator/health/readiness        # 当前实例是否可接收新流量
```

## 本地运行

先配置模型环境变量，不要把真实 key 写进配置文件：

```powershell
$env:OPENAI_API_KEY="your-api-key"
$env:OPENAI_BASE_URL="your-openai-compatible-base-url"
$env:OPENAI_MODEL_NAME="your-model-name"
```

本地默认会开启 Demo JWT 签发器。部署到共享或生产环境时至少设置：

```powershell
$env:SMARTCS_SECURITY_DEMO_ISSUER_ENABLED="false"
$env:SMARTCS_SECURITY_ISSUER="your-identity-provider"
$env:SMARTCS_SECURITY_AUDIENCE="smart-cs-api"
$env:SMARTCS_SECURITY_JWT_SECRET="replace-with-at-least-32-random-bytes"
```

当前 HS256 方案是单体学习基线；跨服务生产架构建议改为独立 IdP 的 RS256/ES256，并让业务服务只
持有验证公钥。完整角色矩阵与 MCP 服务身份边界见
[JWT 统一认证与角色权限说明](docs/客服Agent/客服Agent-48-JWT统一认证与角色权限说明.md)。

启动项目：

```powershell
.\mvnw.cmd spring-boot:run
```

访问页面：

```text
http://localhost:8080
```

使用 Docker Compose 启动完整依赖时，可复制 `.env.example` 后执行：

```powershell
Copy-Item .env.example .env
docker compose up -d --build
docker compose ps
```

Compose 自带 MySQL，宿主机默认端口为 3307，Agent 会等待数据库健康后启动，并通过
`/actuator/health/readiness` 暴露容器健康状态。单独验收真实 MySQL 时可只启动数据库：

```powershell
docker compose up -d mysql
mvn -Dtest=RealMySqlPersistenceIT test
```

默认使用内存数据。如果要开启 MySQL 持久化，先创建数据库：

```sql
CREATE DATABASE smart_cs_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

再设置环境变量：

```powershell
$env:SMARTCS_PERSISTENCE_MODE="jdbc"
$env:SMARTCS_JDBC_URL="jdbc:mysql://localhost:3306/smart_cs_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:SMARTCS_JDBC_USERNAME="root"
$env:SMARTCS_JDBC_PASSWORD="your-password"
$env:SMARTCS_JDBC_CONNECT_TIMEOUT_MS="3000"
$env:SMARTCS_JDBC_SOCKET_TIMEOUT_MS="10000"
$env:SMARTCS_JDBC_QUERY_TIMEOUT_SECONDS="5"
```

启动后，项目会自动创建四张业务表，以及 `cs_agent_session`、`cs_capability_idempotency` 两张 Agent 基础设施表，并写入演示种子数据。默认连接超时为 3 秒、Socket 读取超时为 10 秒、单条 SQL 超时为 5 秒，避免数据库故障时业务请求和 readiness 长期阻塞。业务表、会话与幂等存储分别见 [数据库持久化设计](docs/客服Agent/客服Agent-28-数据库持久化设计.md)、[SessionStore 持久化与恢复说明](docs/客服Agent/客服Agent-43-SessionStore持久化与恢复说明.md) 和 [Capability 幂等持久化与租约说明](docs/客服Agent/客服Agent-44-Capability幂等持久化与租约说明.md)。

默认 RAG 使用本地检索，不依赖外部向量库。如果要演示真 embedding + Qdrant，可以先启动 Qdrant：

```powershell
docker compose up -d qdrant
```

再设置环境变量：

```powershell
$env:SMARTCS_RAG_MODE="embedding-qdrant"
$env:SMARTCS_EMBEDDING_BASE_URL="https://api.openai.com/v1"
$env:SMARTCS_EMBEDDING_API_KEY="your-api-key"
$env:SMARTCS_EMBEDDING_MODEL_NAME="text-embedding-3-small"
$env:SMARTCS_EMBEDDING_DIMENSIONS="1536"
$env:SMARTCS_EMBEDDING_CONNECT_TIMEOUT_MS="3000"
$env:SMARTCS_EMBEDDING_READ_TIMEOUT_MS="10000"
$env:SMARTCS_QDRANT_BASE_URL="http://localhost:6333"
$env:SMARTCS_QDRANT_COLLECTION="smart_cs_knowledge"
$env:SMARTCS_QDRANT_CONNECT_TIMEOUT_MS="3000"
$env:SMARTCS_QDRANT_READ_TIMEOUT_MS="10000"
```

开启后，`KnowledgeTools` 会把 Markdown 知识库向量化后写入 Qdrant，并用用户问题的 query embedding 做相似度检索；如果 embedding 或 Qdrant 暂时不可用，会自动回退本地 RAG。设计说明见 [客服Agent-33-Embedding向量数据库RAG设计.md](docs/客服Agent/客服Agent-33-Embedding向量数据库RAG设计.md)。

默认人工转接使用本地模拟工单。如果要联调桌面售后工单平台，先启动后端项目：

```powershell
cd C:\Users\feasycom\Desktop\demo\after-sales-sla-platform
mvn -s .mvn\settings.xml spring-boot:run
```

再设置 Agent 项目环境变量：

```powershell
$env:SMARTCS_HANDOFF_MODE="after-sales-http"
$env:SMARTCS_AFTER_SALES_BASE_URL="http://localhost:8081"
$env:SMARTCS_AFTER_SALES_TENANT_ID="demo"
$env:SMARTCS_AFTER_SALES_AGENT_USER_ID="agent-service"
$env:SMARTCS_AFTER_SALES_AGENT_USER_ROLE="AGENT"
$env:SMARTCS_AFTER_SALES_CONNECT_TIMEOUT_MS="3000"
$env:SMARTCS_AFTER_SALES_READ_TIMEOUT_MS="10000"
$env:SMARTCS_HANDOFF_RECOVERY_ENABLED="false"
```

后台恢复默认关闭。只有确认售后平台会按 `Idempotency-Key` 返回同一工单后，才将
`SMARTCS_HANDOFF_RECOVERY_ENABLED` 设为 `true`；恢复执行器会使用调用前保存的原请求、短租约和有限
指数退避，多次失败后转为人工复核，不会无限重试。

此时用户触发转人工后，Agent 会调用售后工单平台 `POST /api/tickets` 创建真实工单，返回 `AS-{id}`。设计说明见 [客服Agent-32-售后工单平台联调设计.md](docs/客服Agent/客服Agent-32-售后工单平台联调设计.md)。

售后、Embedding 和 Qdrant 的连接/读取超时均可独立配置，且不能设置为 0。真实慢 HTTP
故障测试和“超时不代表副作用没有发生”的边界见
[外部 HTTP 客户端超时说明](docs/客服Agent/客服Agent-54-外部HTTP客户端超时与故障测试说明.md)。

运行测试：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" test
```

GitHub Actions 会在 push 或 Pull Request 时自动运行 Maven 测试，作为项目的基础质量门禁。CI 详情见 [客服Agent-27-CI质量门禁设计.md](docs/客服Agent/客服Agent-27-CI质量门禁设计.md)。

测试运行后，五类评测会自动输出 JSON 指标报告到：

```text
target/eval-reports/
```

可选运行真实 LLM-as-Judge 评测：
```powershell
$env:OPENAI_API_KEY="your-api-key"
$env:OPENAI_BASE_URL="your-openai-compatible-base-url"
$env:OPENAI_MODEL_NAME="your-judge-model"
.\scripts\run-real-judge-eval.ps1
```

默认 `mvn test` 使用 Fake Judge 保持稳定；上面的脚本会显式开启真实 Judge，并把 `judgeMode` 写入 `target/eval-reports/llm-as-judge.json`。

其中 `summary.json` 是当前聚合入口，`trend.json` 是趋势入口，`history/` 会保存每次评测的历史快照，其余文件对应各类评测明细。

## 质量闭环演示

项目提供了一个 PowerShell 脚本，用来演示“用户负反馈如何变成回归测试”：

```powershell
$env:SMARTCS_MONITOR_RESPONSE_QUALITY_EVAL_FILE="target/demo-quality-loop/response_quality_eval_cases.json"
$env:SMARTCS_MONITOR_EVAL_CANDIDATES_FILE="target/demo-quality-loop/feedback-eval-candidates.json"
.\mvnw.cmd spring-boot:run
```

另开一个 PowerShell 窗口运行：

```powershell
.\scripts\demo-quality-loop.ps1
```

脚本会模拟 `UNHELPFUL` 反馈、审核候选样本、合并临时评测文件，并运行 `ResponseQualityEvaluationTest`。详细说明见 [客服Agent-24-质量闭环演示脚本.md](docs/客服Agent/客服Agent-24-质量闭环演示脚本.md)。

## Docker 运行

复制环境变量示例文件：

```powershell
Copy-Item .env.example .env
```

然后在 `.env` 中填写真实模型配置：

```text
OPENAI_API_KEY=your-api-key
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_MODEL_NAME=your-model-name
SMARTCS_SECURITY_DEMO_ISSUER_ENABLED=true
SMARTCS_SECURITY_JWT_SECRET=replace-with-at-least-32-random-bytes
```

上面的 `true` 只适合本地工作台。共享或生产环境必须改为 `false`，并接入真实身份签发系统。

启动容器：

```powershell
docker compose up --build
```

`docker-compose.yml` 会同时启动固定版本的 MySQL 8.4.0 和 Qdrant。Compose 场景下 Agent 默认使用
JDBC，并等待 MySQL healthy 后再启动；宿主机默认通过 3307 访问数据库，避免占用常见的 3306。
默认 `.env.example` 中 `SMARTCS_RAG_MODE=local`，所以 Agent 不会强制依赖 embedding 服务；需要在容器中演示真向量库时，把 `.env` 改成：

```text
SMARTCS_RAG_MODE=embedding-qdrant
SMARTCS_EMBEDDING_API_KEY=your-api-key
SMARTCS_QDRANT_BASE_URL=http://qdrant:6333
```

Compose 也会透传 `SMARTCS_JDBC_CONNECT_TIMEOUT_MS`、`SMARTCS_JDBC_SOCKET_TIMEOUT_MS` 和
`SMARTCS_JDBC_QUERY_TIMEOUT_SECONDS`，可以在 `.env` 中覆盖默认值。

访问页面和健康检查：

```text
http://localhost:8080
http://localhost:8080/api/health
http://localhost:8080/actuator/health/liveness
http://localhost:8080/actuator/health/readiness
```

停止容器：

```powershell
docker compose down
```

该命令默认保留 `mysql_data` 卷；只有明确希望清空数据库时才使用 `docker compose down -v`。镜像构建、
9 个 Skill 清单、readiness 掉线恢复和 Agent 重启持久化的真实证据见
[真实 MySQL 与 Docker 编排验收说明](docs/客服Agent/客服Agent-59-真实MySQL与Docker编排验收说明.md)。

`.env` 已经被 `.gitignore` 忽略，仓库只保留 `.env.example`，避免把真实 API Key 提交进去。

## 演示用例

- `帮我查一下订单 ORD-001`
- `查看我的订单`
- `我要退货`
- `ORD-004`
- `质量问题`
- `超过七天还能退吗？`
- `发票什么时候开？`
- `我要找人工客服`

## 当前评测

当前评测样本共 36 条：

- 路由评测：16 条。
- 知识库召回评测：8 条。
- 回复质量规则评测：6 条。
- Agent 链路回复评测：3 条。
- LLM-as-Judge 评测：3 条。

最近一次完整测试通过 397 个测试用例；另有 1 项显式真实 MySQL IT 通过。评测详情见 [客服Agent-08-评测报告.md](docs/客服Agent/客服Agent-08-评测报告.md)。

## 文档

- [开发路线图](docs/客服Agent/客服Agent开发路线图.md)
- [架构设计](docs/客服Agent/客服Agent-01-架构设计.md)
- [多轮会话与任务状态设计](docs/客服Agent/客服Agent-02-多轮会话与任务状态设计.md)
- [Agent Trace 调用链设计](docs/客服Agent/客服Agent-03-Agent%20Trace调用链设计.md)
- [MCP 风格工具调用设计](docs/客服Agent/客服Agent-04-MCP风格工具调用设计.md)
- [知识库 RAG 设计](docs/客服Agent/客服Agent-05-知识库RAG设计.md)
- [人工转接机制设计](docs/客服Agent/客服Agent-06-人工转接机制设计.md)
- [端到端评测设计](docs/客服Agent/客服Agent-07-端到端评测设计.md)
- [评测报告](docs/客服Agent/客服Agent-08-评测报告.md)
- [Skill 拆分与接口 DTO 设计](docs/客服Agent/客服Agent-09-Skill拆分与接口DTO设计.md)
- [评测报告 JSON 化设计](docs/客服Agent/客服Agent-10-评测报告JSON化设计.md)
- [接口校验与错误响应设计](docs/客服Agent/客服Agent-11-接口校验与错误响应设计.md)
- [工具输出 DTO 化设计](docs/客服Agent/客服Agent-12-工具输出DTO化设计.md)
- [前端调试工作台设计](docs/客服Agent/客服Agent-13-前端调试工作台设计.md)
- [评测报告可视化设计](docs/客服Agent/客服Agent-14-评测报告可视化设计.md)
- [后续模块化开发计划](docs/客服Agent/客服Agent-15-后续模块化开发计划.md)
- [Monitor 指标与 Eval 闭环设计](docs/客服Agent/客服Agent-16-Monitor指标与Eval闭环设计.md)
- [多轮记忆管理升级设计](docs/客服Agent/客服Agent-17-多轮记忆管理升级设计.md)
- [LLM-as-Judge 评测设计](docs/客服Agent/客服Agent-18-LLM-as-Judge评测设计.md)
- [Docker 部署设计](docs/客服Agent/客服Agent-19-Docker部署设计.md)
- [长期用户偏好记忆设计](docs/客服Agent/客服Agent-20-长期用户偏好记忆设计.md)
- [记忆清除与隐私控制设计](docs/客服Agent/客服Agent-21-记忆清除与隐私控制设计.md)
- [用户反馈闭环设计](docs/客服Agent/客服Agent-22-用户反馈闭环设计.md)
- [反馈样本进入评测设计](docs/客服Agent/客服Agent-23-反馈样本进入评测设计.md)
- [质量闭环演示脚本](docs/客服Agent/客服Agent-24-质量闭环演示脚本.md)
- [面试亮点总结](docs/客服Agent/客服Agent-25-面试亮点总结.md)
- [演示流程](docs/客服Agent/客服Agent-26-演示流程.md)
- [CI 质量门禁设计](docs/客服Agent/客服Agent-27-CI质量门禁设计.md)
- [数据库持久化设计](docs/客服Agent/客服Agent-28-数据库持久化设计.md)
- [独立 MCP Server 设计](docs/客服Agent/客服Agent-29-独立MCPServer设计.md)
- [RAG 向量检索升级设计](docs/客服Agent/客服Agent-30-RAG向量检索升级设计.md)
- [Multi-Agent 路由设计](docs/客服Agent/客服Agent-31-Multi-Agent路由设计.md)
- [售后工单平台联调设计](docs/客服Agent/客服Agent-32-售后工单平台联调设计.md)
- [Embedding 向量数据库 RAG 设计](docs/客服Agent/客服Agent-33-Embedding向量数据库RAG设计.md)
- [可扩展架构改造开发计划](docs/客服Agent/客服Agent-34-可扩展架构改造开发计划.md)
- [Capability 幂等持久化与租约说明](docs/客服Agent/客服Agent-44-Capability幂等持久化与租约说明.md)
- [Liveness 与 Readiness 健康探针说明](docs/客服Agent/客服Agent-45-Liveness与Readiness健康探针说明.md)
- [WorkflowStateMigrator 注册链说明](docs/客服Agent/客服Agent-46-WorkflowStateMigrator注册链说明.md)
- [长期记忆 TTL、容量与脱敏说明](docs/客服Agent/客服Agent-47-长期记忆TTL容量与脱敏说明.md)
- [JWT 统一认证与角色权限说明](docs/客服Agent/客服Agent-48-JWT统一认证与角色权限说明.md)
- [Trace 统一脱敏与隐私边界说明](docs/客服Agent/客服Agent-49-Trace统一脱敏与隐私边界说明.md)
- [Monitor 统一 Turn 结果说明](docs/客服Agent/客服Agent-50-Monitor统一Turn结果与异常指标说明.md)
- [失败生命周期与可靠性故障注入说明](docs/客服Agent/客服Agent-51-失败生命周期与可靠性故障注入说明.md)
- [项目简历与面试完整手册](docs/客服Agent/客服Agent-52-项目简历与面试完整手册.md)
- [应用日志与 MCP 审计安全说明](docs/客服Agent/客服Agent-53-应用日志与MCP审计安全说明.md)
- [外部 HTTP 客户端超时说明](docs/客服Agent/客服Agent-54-外部HTTP客户端超时与故障测试说明.md)
- [人工转接结果未知与假成功修复说明](docs/客服Agent/客服Agent-55-人工转接结果未知与假成功修复说明.md)
- [PendingHandoffStore 持久化基础说明](docs/客服Agent/客服Agent-56-PendingHandoffStore持久化基础说明.md)
- [Outbox 调用前准备与成功结果回放说明](docs/客服Agent/客服Agent-57-Outbox调用前准备与成功结果回放说明.md)
- [Outbox 租约与后台有限恢复说明](docs/客服Agent/客服Agent-58-Outbox租约与后台有限恢复说明.md)
- [真实 MySQL 与 Docker 编排验收说明](docs/客服Agent/客服Agent-59-真实MySQL与Docker编排验收说明.md)

## 简历表达参考

智能客服 Agent：基于 Spring Boot + LangChain4j 实现电商客服 Agent，采用 Agent Skill Package、Workflow、Runtime、Capability Gateway 分层架构，支持多轮任务状态、MCP/JSON-RPC 能力调用、JWT 认证与 RBAC、RAG 知识库、Qdrant 向量检索、Agent Trace、Monitor 反馈闭环、LLM-as-Judge、数据库持久化和售后工单平台联调，提升客服场景下大模型应用的可控性、可观测性和可验证性。

可展开描述：

- 设计 Agent Skill Package / Workflow / Runtime / Capability 分层架构，将指令与知识、任务状态机、通用执行循环和原子业务能力解耦，支持订单查询、商品咨询、退货申请、政策问答和人工转接等客服场景。
- 实现多轮会话状态管理，使用 `ConversationState` 保存任务槽位、短期消息窗口和摘要记忆，支持退货申请等跨轮槽位填充流程。
- 设计 MCP-style 工具注册与调用层，统一 `ToolDefinition`、`ToolCall`、`ToolResult`、`inputSchema` 和 `outputSchema`，并提供 MCP Adapter 接口暴露工具列表和工具调用能力。
- 实现 `/mcp` JSON-RPC 风格入口，支持 `initialize`、`tools/list` 和 `tools/call`，让内部工具注册表具备独立 MCP Server 的协议边界。
- 接入 Spring Security Resource Server，统一校验 JWT 签名、issuer、audience、有效期、租户和角色；区分 CUSTOMER、ADMIN 与 MCP_CLIENT，并禁止 MCP 服务通过普通元数据冒充最终用户。
- 实现 RAG 双模式：默认 local 模式保证测试和演示稳定，高级模式接入 OpenAI-compatible embedding API 与 Qdrant 向量数据库，并在外部服务失败时回退本地检索。
- 实现轻量 Multi-Agent 路由，将用户消息分配到售前、售后、投诉和通用专家域，并通过 Trace 记录路由原因和置信度。
- 实现 Repository 层 `memory` / `jdbc` 双模式，默认内存模式保证测试稳定，开启 JDBC 模式后可将用户、商品、订单和退货记录持久化到 MySQL。
- 实现人工转接 `memory` / `after-sales-http` 双模式，远端结果未知时写入 memory/JDBC Outbox；可选恢复 Worker 通过短租约、原 Idempotency-Key、有限退避和人工复核状态安全推进，不再伪造成功或无限重试。
- 构建 Agent Trace、Monitor 指标和用户反馈闭环，将无用反馈沉淀为 Eval 候选样本，支持审核、导出、半自动合并正式评测集和回归测试。
- 建设五类离线评测，包括路由评测、知识库召回评测、回复质量规则评测、Agent 链路评测和 LLM-as-Judge 评测，并输出 JSON 报告、历史快照和趋势指标；通过 GitHub Actions 在 push / PR 时自动运行 Maven 测试，形成基础 CI 质量门禁。
