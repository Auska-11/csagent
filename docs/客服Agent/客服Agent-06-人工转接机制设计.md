# 智能客服 Agent：人工转接机制设计

## 1. 为什么需要人工转接

客服 Agent 不是所有问题都应该自动处理。

有些场景继续自动回复反而会带来风险：

- 用户明确要求人工客服。
- 用户强烈投诉或情绪激烈。
- 涉及赔偿、补偿、维权、起诉等纠纷。
- 涉及隐私、敏感信息或复杂协商。
- 工具连续失败，自动客服无法确认事实。

所以人工转接不是“Agent 做不了”的失败表现，而是一个成熟客服系统必须具备的边界控制能力。

面试里可以这样讲：

> 我没有让 Agent 对所有问题都强行自动回答，而是设计了 HandoffAction。对于投诉、赔偿、用户明确要求人工等场景，Agent 会收敛自动处理边界，创建人工转接工单，并把当前诉求和上下文摘要交给人工客服。

## 2. 当前实现范围

本阶段已完成人工转接第一版：

- 新增 `HandoffAction`。
- 新增 `HandoffTools`。
- 新增工具能力 `handoff_create`。
- `AgentRuntime` 支持执行 `HandoffAction`。
- Trace 新增：
  - `handoff_requested`
  - `handoff_created`
  - `handoff_failed`
- `CustomerServiceSkill` 能识别转人工场景。
- 转人工优先级高于多轮槽位填充。
- 单元测试覆盖 Skill、Runtime、ToolRegistry 三层。

## 3. 核心类说明

## 3.1 `HandoffAction`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/agent/action/HandoffAction.java
```

作用：表示 Skill 做出了“需要人工介入”的决策。

字段：

```java
private String reason;
private String summary;
private Map<String, Object> context;
```

字段含义：

| 字段 | 说明 |
| --- | --- |
| `reason` | 转人工原因，例如 `COMPLAINT`、`USER_REQUESTED_HUMAN` |
| `summary` | 给人工客服看的问题摘要 |
| `context` | 额外上下文，例如用户 ID、前一个意图、订单号 |

为什么要单独设计 Action？

因为转人工不是普通回复，也不是普通工具调用。

它是 Agent 的一个明确决策：

```text
当前问题不适合继续自动处理，需要人工介入。
```

把它建成 Action 后，Runtime 可以统一执行、记录 Trace，后续评测也能判断“是否应该转人工”。

## 3.2 `HandoffTools`

代码位置：

```text
src/main/java/com/smartcs/smart_cs_agent/tools/HandoffTools.java
```

当前第一版不接真实工单系统，而是生成模拟工单号：

```text
HD-1001
HD-1002
```

为什么仍然要做成工具？

因为真实项目里转人工通常会调用：

- 工单系统
- 在线客服系统
- CRM 系统
- 企业 IM 系统

先做工具边界，后续替换真实系统时，只需要改 `HandoffTools` 或 `ToolRegistry` 的注册逻辑，不需要改 Skill 和 Runtime 的主链路。

## 4. 执行流程

当前转人工链路如下：

```text
用户：我要转人工投诉
  ↓
CustomerServiceSkill 识别强投诉 / 人工诉求
  ↓
返回 HandoffAction(reason, summary, context)
  ↓
AgentRuntime 记录 handoff_requested
  ↓
ToolManager -> ToolRegistry -> HandoffTools
  ↓
创建 handoff_create 工单
  ↓
AgentRuntime 记录 handoff_created
  ↓
返回用户：已转接人工客服，工单号 HD-1001
```

这个流程体现了三个点：

- Skill 只负责判断要不要转人工。
- Runtime 负责执行转人工动作。
- HandoffTools 负责创建工单。

## 5. 转人工规则

当前第一版使用规则判断。

命中以下关键词时会转人工：

| 类型 | 示例关键词 | reason |
| --- | --- | --- |
| 用户要求人工 | `人工`、`转人工`、`人工客服`、`真人客服` | `USER_REQUESTED_HUMAN` |
| 投诉情绪 | `投诉`、`差评`、`太差`、`不满意`、`生气` | `COMPLAINT` |
| 赔偿纠纷 | `赔偿`、`补偿`、`维权`、`律师`、`起诉`、`12315` | `COMPENSATION_OR_DISPUTE` |

后续可以升级成：

- 规则 + LLM 分类。
- 用户情绪识别。
- 工具失败次数触发转人工。
- 多轮无法解决触发转人工。

## 6. 为什么转人工优先级高于槽位填充

这是一个很容易出错的点。

例如：

```text
用户：我要退货
Agent：请提供订单号
用户：我要转人工投诉
```

如果系统仍然按退货流程处理，可能会把“我要转人工投诉”误当成订单号或退货原因。

所以当前设计中：

```text
handoffRoute 优先于 handlePendingSlot
```

也就是用户一旦明确要求人工或投诉，Agent 立即跳出当前多轮任务，进入人工转接。

## 7. Trace 设计

人工转接会记录以下事件：

```text
handoff_requested
handoff_created
handoff_failed
final_answer
```

示例：

```json
{
  "eventType": "handoff_requested",
  "message": "Skill 判断需要转人工",
  "data": {
    "reason": "COMPLAINT",
    "summary": "用户当前消息：我要转人工投诉；转人工原因：COMPLAINT"
  }
}
```

Trace 的价值：

- 前端调试面板能看到为什么转人工。
- 面试演示时能证明 Agent 不是黑盒。
- 后续评测可以统计转人工准确率。

## 8. 测试设计

当前测试覆盖：

```text
src/test/java/com/smartcs/smart_cs_agent/skill/CustomerServiceSkillTest.java
src/test/java/com/smartcs/smart_cs_agent/agent/AgentRuntimeTraceTest.java
src/test/java/com/smartcs/smart_cs_agent/tools/ToolRegistryTest.java
```

重点用例：

- 用户说“我要转人工投诉”，Skill 返回 `HandoffAction`。
- 即使当前有 pending slot，人工诉求也会优先转人工。
- Runtime 会记录 `handoff_requested` 和 `handoff_created`。
- `handoff_create` 工具能创建模拟工单。

当前测试结果：

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
```

## 9. 当前不足

第一版人工转接仍然是轻量版本：

- 工单只存在内存里，没有持久化。
- 没有真实人工客服系统。
- 没有工具失败次数触发转人工。
- 没有 LLM 生成更自然的人工摘要。
- 没有转人工后的状态流转。

这些可以作为后续优化点。

## 10. 后续优化方向

建议后续按这个顺序增强：

1. 增加 `TicketRepository`：
   - 保存工单号、用户、原因、摘要、状态。

2. 增加转人工状态查询：
   - 用户可以问“我的人工工单进度怎么样”。

3. 增加自动摘要：
   - 使用 LLM 总结当前会话，生成更完整的人工摘要。

4. 增加失败触发：
   - 同一工具连续失败 2 次后转人工。
   - 同一槽位连续追问 2 次仍失败后转人工。

5. 增加评测：
   - 投诉类必须转人工。
   - 普通政策类不应误转人工。

## 11. 面试表达

如果面试官问：“什么时候应该转人工？”

可以这样回答：

> 我把转人工设计成 Agent 的边界控制机制。用户明确要求人工、强投诉、赔偿纠纷、维权威胁、隐私敏感或自动工具多次失败时，都不应该继续让 Agent 自行处理。当前第一版用规则识别这些场景，并返回 HandoffAction，由 Runtime 创建人工工单并记录 Trace。

如果面试官问：“为什么不直接回复一句已转人工？”

可以这样回答：

> 因为真实客服系统里转人工通常要创建工单或接入客服系统。我的实现不是硬编码一句话，而是把转人工抽象成 HandoffAction，再通过 ToolRegistry 调用 handoff_create 工具。这样后续可以把 HandoffTools 替换成真实工单系统，同时 Trace 和评测仍然复用。

如果面试官问：“这个模块有什么亮点？”

可以这样回答：

> 亮点是转人工优先级高于多轮任务状态。比如用户上一轮在退货流程中，Agent 正在追问订单号；如果用户下一轮说“我要转人工投诉”，系统会立即跳出槽位填充，创建人工工单，而不是把这句话误当成退货原因。这体现了 Agent 状态机和安全边界的设计。

## 12. 当前结论

人工转接第一版已经完成。

当前项目已经支持：

- 识别人工诉求和投诉纠纷。
- 创建模拟人工工单。
- 生成用户可见的转人工回复。
- 给人工客服保留问题摘要。
- Trace 展示转人工原因和工单创建过程。
- 单元测试覆盖主链路。

这一步完成后，客服 Agent 已经具备“知道什么时候该停止自动处理”的能力。
