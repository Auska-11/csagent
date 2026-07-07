# 客服Agent-13-前端调试工作台设计

## 1. 为什么要做前端调试工作台

客服 Agent 项目如果只有一个聊天框，面试时很难体现深度。因为用户看到的只是最终回复，面试官看不到：

- Agent 识别了什么意图。
- 是否发生了多轮槽位追问。
- 调用了哪个业务工具。
- 工具输入和输出结构是什么。
- LLM 是否调用成功，失败时是否降级。
- 整条链路是否可观测、可复盘、可评测。

所以本项目的前端不是普通聊天页面，而是一个用于开发、调试和面试演示的 Agent 工作台。

## 2. 页面分区

当前页面分为三块：

| 区域 | 作用 |
| --- | --- |
| 左侧 Sidebar | 选择用户、快速发送场景问题、运行多轮 Demo |
| 中间 Chat | 展示用户与 Agent 的真实对话 |
| 右侧 Agent Inspector | 展示 Trace、工具列表、inputSchema、outputSchema 和评测报告 |

这样设计的好处是：中间保留真实客服体验，右侧同时暴露工程链路，既能给普通用户看，也能给开发者和面试官看。

## 3. Demo 脚本

前端内置了几个典型演示脚本：

- 退货多轮演示：`我想退货 -> ORD-004 -> 质量问题`
- 订单查询演示：`帮我查一下订单 ORD-001`
- 人工转接演示：`我要投诉，想找人工客服处理`

注意：Demo 脚本不是伪造结果，而是逐轮调用真实的 `/api/chat/send` 接口。

这样做的原因：

- 可以真实触发后端的 `ConversationStore`。
- 可以真实触发 `CustomerServiceSkill` 的意图识别和槽位填充。
- 可以真实触发工具调用、LLM 生成、降级逻辑和 Trace 记录。
- 面试演示时更可信，不是“写死的前端动画”。

## 4. Agent Inspector

Agent Inspector 当前包含三个 Tab：

### 4.1 Trace

Trace 面板用于展示本轮 Agent 执行链路。

当前支持：

- 按事件类型过滤：All、Tool、LLM、Handoff、Failed、Final。
- 展示 Trace 指标摘要：
  - Tool Events
  - LLM Events
  - Failed
  - Trace Time
- 复制完整 Trace JSON。
- 复制单条 Trace 事件。
- 折叠查看单条事件的 `data`，避免大段 JSON 影响阅读。

这个面板对应后端的 `TraceEvent`：

```java
public class TraceEvent {
    private final String eventType;
    private final int step;
    private final String message;
    private final Map<String, Object> data;
    private final long timestamp;
}
```

面试时可以这样讲：

> 我没有只返回最终答案，而是把 Agent 每一步执行过程结构化记录成 Trace。这样可以定位意图识别、工具调用、LLM 调用、降级和人工转接的具体问题，也方便后续做离线评测和线上监控。

### 4.2 Tools

Tools 面板来自后端只读接口：

```http
GET /api/tools
```

当前支持：

- 搜索工具名称、描述和输出类型。
- 查看工具描述。
- 查看必填参数。
- 查看 `inputSchema`。
- 查看 `outputSchema`。
- 一键复制某个工具的完整 Schema。

这个设计是在模拟 MCP 工具注册表的可观测能力。即使当前工具还在本地 Java 进程中，也先把工具边界、参数结构和输出结构描述清楚，后续迁移到独立 MCP Server 会更自然。

### 4.3 Eval

Eval 面板来自后端只读接口：

```http
GET /api/eval/reports
```

当前支持：

- 展示最新评测报告是否可用。
- 展示报告数量、评测用例总数和平均核心指标。
- 展示每类报告的 summary JSON。
- 展示每类报告的历史运行次数和指标变化。
- 一键复制完整评测报告 JSON。

这个面板让项目从“能运行”进一步变成“能验证”。面试时可以直接说明：每次改 Agent 逻辑后，可以用离线评测报告判断路由、知识库召回、回复质量和 Trace 链路有没有退化。

## 5. 前端状态管理

当前前端没有引入 Vue/React，而是使用原生 JavaScript。

原因是项目重点在 Agent 后端工程能力，前端只承担演示和调试工作台职责。保持原生实现可以减少学习成本，也方便新手理解完整链路。

核心状态在 `state` 对象中：

```javascript
const state = {
    users: [],
    tools: [],
    currentUser: null,
    conversationIds: {},
    messagesByConversation: {},
    tracesByConversation: {},
    trace: [],
    activeTab: 'trace',
    traceFilter: 'all',
    toolSearch: '',
    demoRunning: false
};
```

这里有一个重要设计点：

- 后端保存真实会话状态。
- 前端只保存当前页面展示所需的临时消息、Trace 和选中用户。

这样可以避免前端过早变复杂，同时不影响后端多轮会话能力。

## 6. 后续可优化方向

后续可以继续做：

- 增加 Trace 时间线视图，展示每一步耗时。
- 增加失败事件高亮和失败原因聚合。
- 增加 Eval 失败 case 详情页，展示每个失败样例的期望值和实际值。
- 增加“工具调用回放”，根据 Trace 重新展示一次工具输入输出。
- 增加移动端更紧凑的 Inspector 布局。

## 7. 简历表达

可以写成：

> 设计并实现 Agent 调试工作台，支持多用户会话演示、典型客服 Demo 脚本、Agent Trace 过滤与复制、工具 input/output schema 可视化、工具 Schema 复制和 Eval 评测报告看板，提升客服 Agent 的可观测性、可调试性、可验证性和面试演示效果。
