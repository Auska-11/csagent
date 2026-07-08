# 客服Agent-31-Multi-Agent 路由设计

## 1. 为什么做 Multi-Agent 路由

客服场景天然可以拆成多个专家域：

- 售前：商品、价格、库存、推荐。
- 售后：订单、物流、退换货、发票、保修。
- 投诉：人工、赔偿、维权、差评。
- 通用：无法明确分类时兜底。

如果所有逻辑都塞进一个 Skill，项目后期会越来越难维护。面试时也容易被追问：

- 多 Agent 怎么路由？
- 路由结果能不能观察？
- 路由错了有没有兜底？

所以这一版先做轻量 Multi-Agent 路由。

## 2. 当前设计取舍

当前没有立刻拆出多个完整 Skill 类，而是先做：

```text
路由层 + 专家域标签 + Trace 可观测
```

也就是说：

```text
用户消息
  -> SkillManager.routeAgent()
  -> 得到 pre_sales_agent / after_sales_agent / complaint_agent / general_agent
  -> Trace 记录 agent_routed
  -> 具体执行仍复用稳定的 CustomerServiceSkill
```

这样做的原因：

- 当前 `CustomerServiceSkill` 已经覆盖订单、商品、退货、知识库和人工转接。
- 直接拆成多个 Skill 容易复制大量逻辑。
- 先把路由边界和 Trace 做清楚，后续再拆真实专家 Skill 更稳。

## 3. 路由结果

新增：

```text
AgentRouteResult
```

字段：

```text
routeKey：路由分类，例如 complaint
agentName：专家 Agent 名称，例如 complaint_agent
reason：路由原因，方便 Trace 和面试解释
confidence：规则置信度
```

当前专家 Agent：

| agentName | 场景 |
| --- | --- |
| `pre_sales_agent` | 商品、价格、库存、推荐 |
| `after_sales_agent` | 订单、物流、退换货、保修、发票 |
| `complaint_agent` | 投诉、人工、赔偿、维权 |
| `general_agent` | 通用兜底 |

## 4. Trace 观测

`AgentRuntime` 在每轮开始时记录：

```text
agent_routed
```

Trace data 包含：

```json
{
  "routeKey": "complaint",
  "agentName": "complaint_agent",
  "confidence": 0.95,
  "reason": "命中投诉、人工或赔偿类表达，优先交给投诉/人工路由"
}
```

同时 `skill_selected` 里也会记录 `routedAgent`，让前端调试面板能看到当前消息被分给了哪个专家域。

## 5. 后续怎么演进

当前是第一版规则路由，后续可以逐步升级：

```text
规则路由
  -> 规则 + LLM 路由
  -> 独立专家 Skill
  -> 专家 Agent 独立 Prompt
  -> Monitor 统计各 Agent 成功率
  -> 基于评测结果调整路由规则
```

注意：没有真实线上数据时，不建议过度包装“动态调权”。当前项目更适合讲“路由可观测 + 评测可验证 + 失败有兜底”。

## 6. 验证方式

已补充：

```text
SkillManagerRoutingTest
AgentRuntimeTraceTest
```

覆盖：

- 商品咨询路由到 `pre_sales_agent`。
- 订单/售后路由到 `after_sales_agent`。
- 投诉/人工路由到 `complaint_agent`。
- Runtime Trace 包含路由事件。

运行：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" "-Dtest=SkillManagerRoutingTest,AgentRuntimeTraceTest" test
```

## 7. 面试怎么讲

可以这样讲：

> 我没有一开始把客服 Agent 强行拆成多个重复 Skill，而是先在 `SkillManager` 增加 Multi-Agent 路由层。当前会把用户消息路由到售前、售后、投诉或通用专家域，并把路由结果写入 Trace。这样既能展示 Multi-Agent 编排思想，又不会破坏已经稳定的客服执行链路。

如果被追问“为什么不直接拆多个 Agent”：

> 因为第一版真实业务逻辑还在一个 `CustomerServiceSkill` 里比较稳定，直接拆多个类会出现大量重复代码。我的做法是先固定路由接口和观测数据，等后续功能边界稳定后，再把售前、售后、投诉分别拆成独立 Skill 或独立 Prompt。

