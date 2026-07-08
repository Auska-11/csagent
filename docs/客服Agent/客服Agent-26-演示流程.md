# 客服Agent-26-演示流程

## 1. 演示目标

这份文档用于面试或答辩时快速演示 Smart CS Agent。目标不是把所有接口都跑一遍，而是用 10 到 15 分钟证明三件事：

1. Agent 能完成真实客服任务。
2. Agent 执行过程可观察、可定位。
3. Agent 质量可以被评测和持续改进。

## 2. 演示前准备

### 2.1 启动后端

如果本地有模型配置，可以先设置环境变量：

```powershell
$env:OPENAI_API_KEY="your-api-key"
$env:OPENAI_BASE_URL="your-openai-compatible-base-url"
$env:OPENAI_MODEL_NAME="your-model-name"
```

启动项目：

```powershell
.\mvnw.cmd spring-boot:run
```

访问：

```text
http://localhost:8080
```

健康检查：

```http
GET http://localhost:8080/api/health
```

### 2.2 没有真实模型时怎么讲

如果没有配置真实模型，也可以演示大部分工程能力：

- 工具层 schema。
- MCP Adapter。
- 记忆接口。
- Monitor。
- Eval 报告。
- 质量闭环脚本。
- LLM 失败降级 Trace。

可以说明：

> 项目默认把外部模型依赖做成可配置项，没有 key 时也能通过降级逻辑和测试用例验证核心工程链路。

## 3. 推荐演示路线

建议只演示 4 个场景：

1. 多轮退货申请。
2. 政策知识库问答。
3. Agent Trace 和 Memory 面板。
4. 用户反馈到 Eval 的质量闭环。

这 4 个场景已经能覆盖 Agent 项目的核心深度。

## 4. 场景一：多轮退货申请

### 4.1 页面操作

在前端聊天窗口输入：

```text
我要退货
```

Agent 应该追问订单号。

继续输入：

```text
ORD-004
```

Agent 应该追问退货原因。

继续输入：

```text
质量问题
```

Agent 应该提交退货申请或返回对应退货处理结果。

### 4.2 讲解重点

这条链路重点讲：

- Agent 没有因为第一轮信息不全就乱调用工具。
- `ConversationState` 记住了当前处于退货申请流程。
- 用户第二轮只输入订单号，Agent 仍能理解这是上一轮退货任务的补充信息。
- 槽位齐全后才调用退货工具。

可以这样说：

> 这里体现的是多轮任务状态和槽位填充。客服 Agent 不能只做单轮问答，它要知道当前任务缺什么信息，什么时候该追问，什么时候才能调用业务工具。

## 5. 场景二：订单查询和工具调用

输入：

```text
帮我查一下订单 ORD-001
```

观察前端 Trace：

- 是否出现 `action_planned`。
- 是否出现订单工具调用。
- 工具参数里是否包含 `ORD-001`。
- 工具结果是否返回订单信息。
- 最终回复是否基于工具结果。

讲解重点：

> 订单这种事实型问题不能靠模型编，需要通过工具查询。项目里工具调用被标准化成 `ToolCall` 和 `ToolResult`，Trace 会记录工具名、参数、结果和耗时。

## 6. 场景三：政策知识库问答

输入：

```text
超过七天还能退吗？
```

或：

```text
发票什么时候开？
```

观察 Trace：

- 是否调用 `knowledge_search`。
- 是否命中对应 Markdown 政策。
- 是否有知识库召回结果。
- 没召回时是否出现 `knowledge_miss`。

讲解重点：

> 政策类问题不能让模型凭空回答，所以我把退货、发票、保修、配送等规则放进 Markdown 知识库。当前检索做了查询改写、多路召回和简单 rerank，并且有知识库召回评测。

## 7. 场景四：查看 Memory

可以先输入：

```text
我喜欢蓝牙耳机
```

再输入：

```text
有什么产品推荐吗？
```

然后切到 Memory 面板，观察：

- 当前会话短期记忆。
- 任务记忆。
- 摘要记忆。
- 用户长期偏好。

讲解重点：

> 项目里区分了会话内短期记忆和用户长期偏好。短期记忆服务当前任务，长期记忆用于后续个性化推荐。同时提供清除接口，避免记忆不可控。

## 8. 场景五：查看 Tools 和 MCP Adapter

### 8.1 前端查看

切到 Tools 面板，观察：

- 工具名称。
- 工具描述。
- input schema。
- output schema。

### 8.2 接口查看

```http
GET http://localhost:8080/api/tools
```

```http
GET http://localhost:8080/api/mcp/tools
```

调用工具：

```http
POST http://localhost:8080/api/mcp/tools/call
Content-Type: application/json

{
  "name": "order_lookup",
  "arguments": {
    "order_id": "ORD-001"
  }
}
```

讲解重点：

> 我把工具层做成了 MCP-style Adapter。虽然不是独立 MCP Server，但已经具备工具列表、工具调用、输入输出 schema 和统一返回结构，后续迁移到真正 MCP Server 时成本更低。

## 9. 场景六：Monitor 和风险告警

打开 Monitor 面板，观察：

- 总请求数。
- 工具成功率。
- LLM 失败率。
- 平均耗时。
- 转人工次数。
- 用户反馈有用率。
- 风险告警。
- Eval 候选样本状态。

可以点击某条回复的“有用”或“无用”反馈。

讲解重点：

> Monitor 不是只看系统是否活着，而是关注 Agent 链路质量。比如工具失败率高，说明业务工具或参数抽取有问题；无用反馈多，说明回复质量或召回质量需要优化。

## 10. 场景七：质量闭环脚本

为了不污染正式评测文件，先用临时路径启动服务：

```powershell
$env:SMARTCS_MONITOR_RESPONSE_QUALITY_EVAL_FILE="target/demo-quality-loop/response_quality_eval_cases.json"
$env:SMARTCS_MONITOR_EVAL_CANDIDATES_FILE="target/demo-quality-loop/feedback-eval-candidates.json"
.\mvnw.cmd spring-boot:run
```

另开一个 PowerShell 窗口：

```powershell
.\scripts\demo-quality-loop.ps1
```

脚本会演示：

```text
提交 UNHELPFUL 反馈
-> 生成 Eval 候选样本
-> 审核为 APPROVED
-> 补齐断言后合并临时评测文件
-> 运行 ResponseQualityEvaluationTest
```

讲解重点：

> 这条链路是项目的质量闭环。用户反馈不是只存在数据库里，而是能进入评测候选池，经过人工审核和断言补齐后变成回归测试，防止同类问题反复出现。

## 11. 场景八：运行评测

运行完整测试：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" test
```

运行后查看：

```text
target/eval-reports/
```

重点文件：

- `summary.json`
- `trend.json`
- `history/`
- `llm-as-judge.json`

讲解重点：

> 我把评测结果输出成 JSON 报告，并保存历史快照和趋势数据。这样不只是看某次测试是否通过，也能观察项目质量变化。

## 12. 可选：真实 LLM-as-Judge

默认 `mvn test` 使用 Fake Judge，保证测试稳定。如果要演示真实模型 Judge：

```powershell
$env:OPENAI_API_KEY="your-api-key"
$env:OPENAI_BASE_URL="your-openai-compatible-base-url"
$env:OPENAI_MODEL_NAME="your-judge-model"
.\scripts\run-real-judge-eval.ps1
```

讲解重点：

> 默认测试不依赖外部模型，避免网络和费用影响；真实 Judge 作为手动脚本运行，用于生成更接近线上评估的报告。

## 13. 面试现场时间安排

### 5 分钟版本

1. 讲项目一句话。
2. 演示多轮退货申请。
3. 打开 Trace 说明工具调用。
4. 打开 Eval 或 Monitor 面板。

### 10 分钟版本

1. 讲架构分层。
2. 演示多轮退货申请。
3. 演示政策问答和 RAG Trace。
4. 演示 Tools schema 和 MCP Adapter。
5. 演示 Monitor 反馈。

### 15 分钟版本

1. 讲架构分层。
2. 演示退货多轮。
3. 演示订单工具调用。
4. 演示政策 RAG。
5. 演示 Memory。
6. 演示 Monitor。
7. 跑质量闭环脚本或展示脚本文档。
8. 展示 Eval 报告。

## 14. 常见演示风险

### 14.1 模型接口不可用

处理方式：

- 说明模型配置通过环境变量注入。
- 展示 LLM 失败降级 Trace。
- 改演示工具、Monitor、Eval 和质量闭环。

### 14.2 现场时间不够

处理方式：

- 只演示多轮退货申请和 Trace。
- 口头补充 Monitor/Eval。

### 14.3 前端页面没加载

处理方式：

- 用接口演示 `/api/chat/send`。
- 用 `/api/tools`、`/api/mcp/tools`、`/api/monitor/summary` 展示工程能力。

### 14.4 评测运行较慢

处理方式：

- 只运行单个测试类。
- 或展示 `target/eval-reports/` 里已有报告。

## 15. 演示结尾话术

可以这样收尾：

> 这个项目目前已经覆盖了客服 Agent 的核心闭环：会话状态、工具调用、知识库、Trace、Monitor、反馈和 Eval。后续如果继续扩展，我会优先把模拟 Repository 换成真实数据库，把 MCP-style Adapter 拆成独立 MCP Server，再把 RAG 升级为向量检索和真实 rerank。

