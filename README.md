# Smart CS Agent

Smart CS Agent 是一个面向电商客服场景的 Java Agent 项目，基于 Spring Boot 和 LangChain4j 构建。

这个项目不是简单的“大模型聊天 Demo”，而是把客服 Agent 拆成可解释、可测试、可扩展的任务执行系统：Agent 先判断用户意图，再根据需要追问信息、调用业务工具、检索知识库、生成客服回复，并把每一步执行过程记录到 Trace。

## 当前能力

- 订单查询：支持指定订单查询、查询当前用户全部订单、缺少订单号时主动追问。
- 商品咨询：支持商品搜索、价格和库存咨询。
- 退换货：支持退货进度查询，以及“我要退货 -> 追问订单号 -> 追问原因 -> 提交退货申请”的多轮流程。
- 多轮记忆：支持短期消息窗口、任务记忆快照、规则摘要压缩，Trace 会记录 `memory_loaded` / `memory_compressed`，前端 Memory 面板可查看当前会话记忆。
- 长期用户偏好：支持记录“我喜欢蓝牙耳机”等商品偏好，后续泛化推荐时可读取用户长期记忆，Trace 会记录 `user_memory_updated` / `user_memory_used`，并支持清空会话记忆和用户长期记忆。
- 知识库问答：支持退货政策、发票、保修、配送、转人工规则等 Markdown 知识库检索，并已增强查询改写、多路召回、简单 rerank 和 `knowledge_miss` Trace。
- 人工转接：支持用户主动要求人工、投诉、赔偿或维权类表达，创建模拟人工工单。
- Agent Trace：接口和前端调试面板可以看到 Action、工具调用、LLM 调用、转人工、耗时等事件。
- 前端调试工作台：支持多用户会话、典型 Demo 脚本、Trace 过滤/复制、Trace 指标摘要，以及工具 input/output schema 可视化。
- 评测报告看板：前端 Eval 面板可以展示最新评测概览、历史趋势和核心指标变化。
- LLM 降级：模型调用失败时不会直接中断接口，会基于工具结构化结果生成模板回复，并记录 `llm_failed` Trace。
- MCP-style 工具层：工具具备统一 `ToolDefinition`、`ToolCall`、`ToolResult`、`inputSchema`、`outputSchema` 和只读工具列表接口，订单、商品、退货、用户和人工转接等业务工具已升级为明确 DTO 输出。
- MCP Adapter：新增 `GET /api/mcp/tools` 和 `POST /api/mcp/tools/call`，把内部工具注册表暴露成更接近 MCP `tools/list`、`tools/call` 的协议边界。
- Monitor 指标与反馈闭环：支持 `GET /api/monitor/summary`、`POST /api/monitor/feedback`、`GET /api/monitor/feedback/candidates`、候选样本审核接口、Eval case 草稿导出接口和半自动合并正式评测集接口，基于 Trace 聚合整体/按工具成功率、LLM 失败率、转人工次数、平均耗时、用户反馈有用率、候选样本审核状态数量和负反馈问题类型分布，并能把无用反馈沉淀成 Eval 待审核候选样本；候选样本会持久化到本地 JSON 文件，服务重启后仍可继续审核；Monitor Summary 会进一步生成结构化风险告警，前端 Monitor 面板可以直接看到工具失败率、LLM 失败率、低有用反馈率、不安全承诺和内部实现泄露等风险。
- 接口工程化：聊天接口使用明确 DTO、Spring Validation 和统一 `ApiErrorResponse`。
- 部署工程化：支持 Docker 多阶段构建、`docker compose` 一键启动、环境变量注入模型配置，并提供 `/api/health` 健康检查接口。
- 离线评测：覆盖路由、知识库召回、回复质量规则、Agent 链路回复、LLM-as-Judge 五类评测，并输出 JSON 总览、历史快照和趋势指标。

## 架构概览

```text
前端页面
  -> ChatApiController
  -> ChatApiRequest / ChatRequest
  -> CustomerServiceAgent
  -> ConversationStore / TurnContext
  -> AgentRuntime
  -> CustomerServiceSkill
  -> AgentAction
  -> ToolManager / ToolRegistry
  -> OrderTools / ProductTools / ReturnTools / UserTools / KnowledgeTools / HandoffTools
  -> Repository 模拟数据层 / Markdown 知识库
```

核心设计取舍：

- `Skill` 负责决策下一步做什么，`Runtime` 负责执行，`Tool` 负责业务能力。
- 高频明确场景优先用规则，表达模糊时再用 LLM 兜底。
- 当前输入、跨轮状态、本轮执行过程分别放在 `ChatRequest`、`ConversationState`、`TurnContext`。
- 工具层先做本地 MCP-style 边界，后续再演进为独立 MCP Server。
- 先做稳定、可解释的规则评测，再补 LLM-as-Judge。

## 主要接口

发送聊天消息：

```http
POST /api/chat/send
Content-Type: application/json

{
  "message": "我要退货",
  "userId": "USER-001",
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

查看模拟用户：

```http
GET /api/chat/users
```

查看当前会话记忆：

```http
GET /api/chat/conversations/{conversationId}/memory
```

查看用户长期记忆：

```http
GET /api/chat/users/{userId}/memory
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
```

查看 MCP Adapter 工具列表：

```http
GET /api/mcp/tools
```

通过 MCP Adapter 调用工具：

```http
POST /api/mcp/tools/call
Content-Type: application/json

{
  "name": "order_lookup",
  "arguments": {
    "order_id": "ORD-001"
  }
}
```

查看 Monitor 在线指标：

```http
GET /api/monitor/summary
```

提交用户反馈：

```http
POST /api/monitor/feedback
Content-Type: application/json

{
  "conversationId": "conv-USER-001-001",
  "userId": "USER-001",
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
GET /api/health
```

## 本地运行

先配置模型环境变量，不要把真实 key 写进配置文件：

```powershell
$env:OPENAI_API_KEY="your-api-key"
$env:OPENAI_BASE_URL="your-openai-compatible-base-url"
$env:OPENAI_MODEL_NAME="your-model-name"
```

启动项目：

```powershell
.\mvnw.cmd spring-boot:run
```

访问页面：

```text
http://localhost:8080
```

运行测试：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" test
```

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
```

启动容器：

```powershell
docker compose up --build
```

访问页面和健康检查：

```text
http://localhost:8080
http://localhost:8080/api/health
```

停止容器：

```powershell
docker compose down
```

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

最近一次完整测试通过 99 个测试用例。评测详情见 [客服Agent-08-评测报告.md](docs/客服Agent/客服Agent-08-评测报告.md)。

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

## 简历表达参考

智能客服 Agent：基于 Spring Boot + LangChain4j 实现电商客服 Agent，采用 Skill / Runtime / Tool 分层架构，将意图识别、工具调用、业务查询、知识库检索和回复生成解耦；支持多轮槽位填充、三级会话记忆、长期用户偏好记忆、MCP-style 工具 input/output schema、MCP Adapter 工具协议接口、业务工具 DTO 输出、Agent Trace、RAG 查询改写、多路召回、简单 rerank、Monitor 在线指标、结构化风险告警、用户反馈闭环、负反馈 Eval 候选样本沉淀与审核、本地 JSON 持久化、负反馈问题类型分类、审核通过样本导出为 Eval case 草稿、人工补齐断言后半自动合并正式评测集、人工转接、LLM 失败降级、接口参数校验、统一错误响应、Docker 多阶段构建部署和五类离线评测，并提供前端 Agent 调试工作台用于演示 Demo 脚本、Trace 过滤、链路指标、Memory 面板、工具 Schema、Monitor 指标、Monitor 告警、用户反馈有用率、Eval 候选样本审核/导出/合并和 Eval 评测看板，可生成 JSON 指标、历史快照、趋势报告和 LLM-as-Judge 四维评分报告，提升客服场景下大模型应用的可控性、可观测性、可验证性和可复现部署能力。
