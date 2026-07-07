# 客服Agent-15-后续模块化开发计划

## 1. 为什么要重新拆模块

当前 Smart CS Agent 已经完成了第一阶段核心闭环：

- 多轮会话与任务状态。
- Skill / Runtime / Tool 分层。
- MCP-style 工具注册表。
- 工具 inputSchema / outputSchema。
- Agent Trace。
- 知识库 RAG 第一版。
- 人工转接。
- LLM 失败降级。
- 离线评测与 Eval 看板。
- 前端 Agent Inspector。
- Monitor 指标、用户反馈和 Eval 候选样本。

接下来不适合继续零散加功能，而应该按模块推进。

这样做有三个好处：

- 开发时不容易乱，每次只聚焦一个技术亮点。
- 文档和代码能同步演进，后面方便复盘。
- 面试时可以按模块讲项目深度，而不是堆一长串功能名。

## 2. 模块优先级总览

| 优先级 | 模块 | 目标 | 面试亮点 |
| --- | --- | --- | --- |
| P0 | 工具协议与 MCP 边界增强 | 把当前 MCP-style 工具层继续标准化，先提供 MCP Adapter 第一版 | Agent 与工具解耦、工具协议化、可迁移到独立 MCP Server |
| P0 | 监控指标与 Eval 闭环增强 | 基于 Trace 和评测报告沉淀可观测指标 | 不只会聊天，还能验证和监控 Agent |
| P1 | RAG 检索增强 | 加查询改写、多路召回、召回失败 Trace | 解决知识库召回不全和检索质量问题 |
| P1 | 多轮记忆管理升级 | 增加短期记忆、任务记忆、摘要记忆 | 控制长对话上下文，减少状态丢失 |
| P1 | LLM-as-Judge 评测 | 用模型对回复做多维度评分 | 从规则评测升级到语义评测 |
| P2 | Docker 与部署工程化 | 支持一键启动和面试演示部署 | 工程完整度、可复现运行 |

## 3. 模块一：工具协议与 MCP 边界增强

### 3.1 当前状态

当前项目已经有：

- `ToolDefinition`
- `ToolParameterDefinition`
- `ToolCall`
- `ToolResult`
- `ToolRegistry`
- `ToolManager`
- `GET /api/tools`
- 工具 `inputSchema`
- 工具 `outputSchema`

这些已经接近 MCP 的 tools/list 思路。

但是当前还不是完整 MCP Server，因为：

- 工具执行仍然是 Agent Runtime 内部直接调用。
- 没有独立的 tools/call 风格接口。
- 没有清晰区分“Agent 内部调用”和“外部协议调用”。
- 没有 MCP Adapter 层，把内部工具模型转换成协议模型。

### 3.2 第一版目标

先不急着引入复杂 MCP SDK，也不急着拆独立进程。

第一版先做一个轻量 MCP Adapter：

- `GET /api/mcp/tools`：返回 MCP 风格工具列表。
- `POST /api/mcp/tools/call`：按工具名和参数调用工具。
- 返回结构统一包含：
  - `success`
  - `toolName`
  - `data`
  - `message`
  - `errorCode`
- 继续复用已有 `ToolRegistry`，避免重复实现业务工具。

### 3.3 为什么先做 Adapter

直接做完整 MCP Server 会引入较多协议细节，对当前项目来说容易过重。

Adapter 的好处是：

- 保留现有 Spring Boot 项目结构。
- 先把工具协议边界讲清楚。
- 后续迁移到独立 MCP Server 时，只需要把 Adapter 层搬出去。
- 新手更容易理解：内部模型和外部协议模型之间有一层转换。

### 3.4 开发任务

- 新增 `McpToolCallRequest` DTO。已完成。
- 新增 `McpToolResponse` DTO。已完成。
- 新增 `McpToolController`。已完成。
- 增加接口测试。已完成：
  - 能列出工具。
  - 能调用 `order_lookup`。
  - 缺少工具名时返回错误。
  - 工具不存在时返回标准失败结果。
- README 增加 MCP Adapter 接口说明。已完成。
- 文档 04 和本文档同步状态。已完成。

### 3.5 验收标准

- `GET /api/mcp/tools` 能返回工具列表和 schema。
- `POST /api/mcp/tools/call` 能调用至少一个真实工具。
- 工具调用失败不会抛出原始异常给前端。
- 测试通过。
- 面试时能讲清楚：当前是 MCP Adapter，后续可以独立拆成 MCP Server。

### 3.6 面试表达

> 我没有把工具调用写死在 Runtime 里，而是先抽象出 ToolDefinition、ToolCall、ToolResult 和 ToolRegistry。后续又补了一层 MCP Adapter，把内部工具注册表暴露成 tools/list 和 tools/call 风格接口。这样 Agent 与工具能力之间有清晰协议边界，未来可以把工具层独立成 MCP Server。

## 4. 模块二：监控指标与 Eval 闭环增强

### 4.1 当前状态

当前已有：

- Trace 事件。
- 工具耗时。
- LLM 调用失败 Trace。
- Eval JSON 报告。
- Eval 前端面板。

### 4.2 第一版目标

新增一个轻量 Monitor API：

- 从最近一次 Trace 或内存统计中计算：
  - 工具调用次数。
  - 工具成功率。
  - 按工具名统计成功率和平均耗时。
  - LLM 调用次数。
  - LLM 失败率。
  - 人工转接次数。
  - 平均工具耗时。
- 增加用户反馈采集：
  - 用户可以对回复点击有用或无用。
  - Monitor 聚合反馈数和有用率。
  - 无用反馈自动生成 Eval 待审核候选样本。
  - 候选样本可以人工审核为 `APPROVED` 或 `REJECTED`。
  - 审核通过的候选样本可以导出为 Eval case 草稿。
- 前端增加 Monitor 卡片。

当前第一版实现：

- 新增 `MonitorService`，基于 `ChatResponse.trace` 做内存聚合。
- 新增 `MonitorSummary` 和 `MonitorTurnSnapshot` DTO。
- 新增 `ToolMonitorSnapshot`，展示单个工具的成功率和平均耗时。
- 新增 `FeedbackRating` 和 `FeedbackRecord`，记录用户有用/无用反馈。
- 新增 `FeedbackEvalCandidate`，把无用反馈沉淀成待审核评测候选。
- 新增 `FeedbackEvalCandidateStatus`，明确候选样本审核状态。
- 新增 `FeedbackEvalCaseDraft`，承载审核通过后的评测草稿。
- 新增 `GET /api/monitor/summary`。
- 新增 `POST /api/monitor/feedback`。
- 新增 `GET /api/monitor/feedback/candidates`。
- 新增 `POST /api/monitor/feedback/candidates/{candidateId}/review`。
- 新增 `GET /api/monitor/feedback/candidates/export`。
- `ChatApiController` 在每轮聊天成功后调用 `monitorService.recordTurn(response)`。
- 前端 Agent Inspector 新增 Monitor Tab。
- 前端普通 Agent 回复下方新增有用/无用反馈按钮。
- 前端 Monitor 面板新增 Eval Candidates 列表。
- 前端 Monitor 面板新增候选样本通过/拒绝审核按钮。
- 前端 Monitor 面板新增导出样本草稿按钮。
- Monitor Summary 新增 `PENDING_REVIEW` / `APPROVED` / `REJECTED` 候选样本状态数量。
- 新增 `MonitorServiceTest` 和 `MonitorControllerTest`。

### 4.3 验收标准

- 每次聊天后能更新 Monitor 指标。
- 指标可以在前端看到。
- 指标来源能追溯到 Trace。
- 可以按工具名定位哪个工具成功率较低或耗时较高。
- 用户点击反馈后，Monitor 能展示反馈总数、有用率和最近反馈。
- 用户点击无用后，Monitor 能展示待审核 Eval 候选样本。
- 用户审核候选样本后，Monitor 能展示审核状态和备注。
- 审核通过的样本能导出为 JSON 草稿。

## 5. 模块三：RAG 检索增强

### 5.1 当前状态

当前知识库检索是轻量本地检索：

- 标题匹配。
- 关键词匹配。
- 正文片段匹配。

### 5.2 第一版目标

先做不依赖向量数据库的增强：

- 查询改写：把用户问题改写成更适合检索的关键词。已完成第一版。
- 多路召回：标题、关键词、正文分开计分。已完成第一版。
- 简单 rerank：按命中位置和关键词覆盖重新排序。已完成第一版。
- 召回失败 Trace：没有找到知识时记录 `knowledge_miss`。已完成第一版。

当前第一版实现：

- `KnowledgeTools` 新增查询改写和查询变体。
- `KnowledgeSearchResult` 新增 `matchedTerms`、`matchedFields`、`queryVariants`、`rerankReason`。
- `knowledge_search` 的 outputSchema 补充命中解释字段。
- `AgentRuntime` 在知识库无可靠命中时记录 `knowledge_miss`。
- 前端 Trace 过滤新增 RAG 选项。
- 知识库召回评测从 6 条扩展到 8 条。

### 5.3 验收标准

- 知识库召回评测通过。
- 前端 Trace 能看到知识库召回详情。
- 能讲清楚“召回不全时怎么优化”。

## 6. 模块四：多轮记忆管理升级

### 6.1 当前状态

当前已有：

- `ConversationStore`
- `ConversationState`
- 任务槽位
- 等待用户补充的信息

### 6.2 第一版目标

把记忆拆成三层：

- 短期记忆：最近几轮消息。已完成第一版。
- 任务记忆：当前意图、槽位、关键任务状态。已完成第一版。
- 摘要记忆：长会话压缩后的摘要。已完成第一版规则压缩。
- 长期用户偏好：跨会话保存商品偏好。已完成第一版。

当前第一版实现：

- `ConversationState` 将 `messages` 升级为短期消息窗口。
- 新增 `summaryMemory`、`summaryVersion`、`summaryUpdatedAt`。
- 新增 `ConversationMemorySnapshot` 作为对外只读快照。
- `AgentRuntime` 每轮记录 `memory_loaded`，发生压缩时记录 `memory_compressed`。
- LLM 调用时会把摘要记忆补充进上下文。
- `ChatApiController` 新增 `GET /api/chat/conversations/{conversationId}/memory`。
- `UserMemoryStore` 新增长期用户偏好记忆，支持记录和读取商品偏好。
- `ChatApiController` 新增 `GET /api/chat/users/{userId}/memory`。
- `ChatApiController` 新增会话记忆和用户长期记忆清除接口。
- 前端 Agent Inspector 新增 Memory Tab，并展示 User Long-Term Memory。
- 前端 Memory 面板新增清空会话记忆和清空长期记忆按钮。
- 新增 `ConversationStateMemoryTest` 覆盖压缩和任务记忆快照。
- 新增 `UserMemoryStoreTest` 和 Skill 偏好记忆测试。
- 新增文档 [客服Agent-20-长期用户偏好记忆设计.md](客服Agent-20-长期用户偏好记忆设计.md)。
- 新增文档 [客服Agent-21-记忆清除与隐私控制设计.md](客服Agent-21-记忆清除与隐私控制设计.md)。

### 6.3 验收标准

- 多轮退货流程不丢状态。
- 最近消息可以用于回复生成。
- 超过阈值后生成或更新摘要。
- 前端可以查看当前会话记忆。
- 用户说过“我喜欢蓝牙耳机”后，泛化推荐可以参考这个偏好。
- 用户可以主动清空当前会话记忆或长期商品偏好。

## 7. 模块五：LLM-as-Judge 评测

### 7.1 当前状态

当前评测主要是规则评测，优点是稳定、可重复。

不足是：

- 很难判断语气是否自然。
- 很难判断回复是否足够完整。
- 很难覆盖开放式回答。

### 7.2 第一版目标

新增 Judge 评测结构：

- 准确性：是否忠实于工具和知识库结果。已完成第一版。
- 完整性：是否回答用户核心问题。已完成第一版。
- 客服语气：是否礼貌、清晰、安抚用户。已完成第一版。
- 安全性：是否承诺了不该承诺的内容。已完成第一版。

第一版可以先保留为可选测试，不强依赖真实模型环境。

当前第一版实现：

- 新增 `LlmJudgePromptBuilder`，构造四维度 Judge Prompt。
- 新增 `LlmJudgeEvaluator`，负责调用 Judge 模型并解析 JSON。
- 新增 `LlmJudgeResult`，承载结构化评分结果。
- 新增 `llm_judge_eval_cases.json`，覆盖安全回复、危险承诺、信息不完整三类样例。
- 新增 `LlmAsJudgeEvaluationTest`，使用 Fake Judge LLM 保证 `mvn test` 稳定。
- 输出 `target/eval-reports/llm-as-judge.json`，并自动进入 summary/trend。

### 7.3 验收标准

- 有清晰 prompt。
- 有结构化评分 JSON。
- 有测试样例。
- 模型不可用时不影响普通单元测试。

## 8. 模块六：Docker 与部署工程化

### 8.1 第一版目标

- 新增 `Dockerfile`。已完成。
- 新增 `docker-compose.yml`。已完成。
- 新增 `.dockerignore` 和 `.env.example`。已完成。
- README 增加 Docker 启动说明。已完成。
- 环境变量通过 compose 注入。已完成。
- 新增 `/api/health` 健康检查接口。已完成。

当前第一版实现：

- `Dockerfile` 使用 Maven + JDK17 构建，再使用 JRE17 运行 jar。
- `docker-compose.yml` 暴露 `8080:8080`，并从 `.env` 注入 `OPENAI_API_KEY`、`OPENAI_BASE_URL` 和 `OPENAI_MODEL_NAME`。
- `application.yml` 不再硬编码真实 API Key，而是读取环境变量。
- `.gitignore` 忽略 `.env` 和 `.env.*`，只保留 `.env.example`。
- `HealthCheckController` 提供 `GET /api/health`，用于确认后端服务是否启动。
- 新增文档 [客服Agent-19-Docker部署设计.md](客服Agent-19-Docker部署设计.md)。

### 8.2 验收标准

- 本地可以一条命令启动。
- 不把 API Key 写入镜像。
- 能通过 `/api/health` 判断服务是否存活。
- 面试演示时能说明部署方式。

## 9. 推荐开发顺序

接下来建议按这个顺序推进：

1. 工具协议与 MCP 边界增强。
2. Monitor 指标与 Eval 闭环增强。
3. RAG 检索增强。
4. 多轮记忆管理升级。已完成第一版。
5. LLM-as-Judge。已完成第一版。
6. Docker 部署。已完成第一版。

原因：

- MCP Adapter 最贴近当前工具层，改动可控，面试亮点明显。
- Monitor 可以复用已有 Trace 和 Eval，看得见成果。
- RAG、Memory、LLM-as-Judge 和 Docker 第一版都已经完成，后续可以继续做长期偏好记忆和真实模型 Judge 可选评测。
- LLM-as-Judge 和 Docker 属于后期增强，所以第一版保持轻量，不影响主链路稳定性。

## 10. 当前执行状态

| 模块 | 状态 |
| --- | --- |
| 工具协议与 MCP 边界增强 | 第一版已完成 |
| Monitor 指标与 Eval 闭环增强 | Trace 指标、用户反馈、候选样本、审核和草稿导出第一版已完成 |
| RAG 检索增强 | 第一版已完成 |
| 多轮记忆管理升级 | 第一版已完成 |
| LLM-as-Judge 评测 | 第一版已完成 |
| Docker 与部署工程化 | 第一版已完成 |
