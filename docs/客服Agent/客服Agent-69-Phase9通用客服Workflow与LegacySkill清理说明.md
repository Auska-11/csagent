# 客服 Agent Phase 9 通用客服 Workflow 与 Legacy Skill 清理说明

## 1. 本批结果

Phase 9.2 已把 `customer_service` 从“旧 Java Skill 的适配结果”替换为真正的
`GeneralCustomerServiceWorkflow`。稳定 Workflow ID 没有变化，因此 `customer-service` Skill Package
绑定和已有会话可见契约保持兼容。

生产主链路现在是：

```text
CustomerServiceAgent
  -> AgentRuntime
  -> WorkflowRouter
  -> GeneralCustomerServiceWorkflow 或专业 Workflow
  -> ActionHandler
```

以下生产类型已经删除：

- `Skill`：旧 Java 决策接口。
- `CustomerServiceSkill`：同时包含路由、槽位、业务动作和 LLM fallback 的大类。
- `LegacySkillWorkflowAdapter`：把旧 Skill 包装成 Workflow 的迁移桥梁。
- `WorkflowBeanConfiguration`：把旧 Skill 注册成 Spring Workflow 的兼容配置。
- `CustomerServicePromptBuilder`：只服务于旧大 Skill 的重复 Prompt 构造器。

## 2. GeneralCustomerServiceWorkflow 的边界

### 2.1 它负责什么

- 打招呼、感谢和简单闲聊。
- 用户只说“我有个问题”等模糊输入时，提出一个最关键的澄清问题。
- 通过统一 `LLMActionHandler` 获得超时、失败降级、Skill reference 和脱敏 Trace。

### 2.2 它明确不负责什么

- 不查询订单、商品、退货、用户资料或政策。
- 不提交退货，不创建人工工单。
- 不根据模型常识编造价格、库存、物流、退款进度或平台政策。

它的 match 分数固定为 0.20，低于 Router 默认阈值 0.25。专业 Workflow 只要可靠命中就会胜出；全部
候选都不可靠时，Router 才根据 `fallbackWorkflowId=customer_service` 选择它。这比旧 Skill 固定返回
0.8 更符合“通用兜底”的含义，也避免抢走专业业务请求。

## 3. 为什么通用 Skill Package 是零 Capability

`skills/customer-service/manifest.yaml` 现在声明：

```yaml
workflow: customer_service
allowed_capabilities: []
risk_level: read_only
```

旧 manifest 曾授权订单、商品、退货、用户、知识和人工工单等 9 个能力。新通用 Workflow 实际一个都
不需要，继续保留授权会形成不必要的攻击面。

因此 `AgentSkillDefinition` 现在允许空 allowlist。空集合表示“纯 Prompt Skill 的最小权限”，不是漏配。
如果此 Workflow 以后错误返回 `CapabilityAction`，Handler 仍会因为 allowlist 为空而拒绝调用。

通用 Skill Package 也删除了意图分类和政策 reference，只在 `response_generation` 时加载角色与澄清模板。
订单、政策等资料归各自专业 Skill Package 管理，避免同一知识在两个包中漂移。

## 4. 测试为什么也必须迁移

以前若 `SkillManager` 没有 Router，会临时创建 `LegacySkillWorkflowAdapter`。这使纯 Java 测试和生产走
两条不同链路，也让 Adapter 无法真正删除。

现在 Runtime 没有 Router 会直接失败。测试通过 `WorkflowTestSupport` 显式创建真实
`WorkflowRegistry -> WorkflowRouter`，但仍使用内存 Repository 和 Fake LLM，不增加外部依赖。

本批迁移了以下测试类型：

- 自定义 Action Handler 扩展测试。
- Runtime 步骤预算和整轮时间预算测试。
- Skill Package Capability allowlist 测试。
- Tool、LLM、Handoff 和 Trace 脱敏测试。
- 四轮退货 Characterization Test。
- Agent 回复链路 Eval 与 16 条首次 Workflow 路由 Eval。

旧的三轮退货测试在业务上存在“没有确认就提交”的问题。本批改为四轮：

```text
我要退货 -> 追问订单号
ORD-003 -> 校验订单归属并追问原因
质量问题 -> 展示摘要并等待确认
确认提交 -> 才执行 return.apply
```

## 5. Eval 发现并修复的真实路由缺陷

迁移后的真实 Router Eval 最初只有 15/16 通过：

```text
输入：我想退这个订单 ORD-004，不想要了
错误结果：order_query
原因：订单号匹配 0.97，但 ReturnApplyWorkflow 没覆盖“想退/不想要”表达
```

修复方式不是修改 Eval 期望，而是：

1. `ReturnApplyWorkflow` 补充一次性退货表达。
2. `OrderQueryWorkflow` 复用共享 `CustomerServiceIntentRules`，遇到退货意图主动让路。
3. 路由基线新增该句回归用例。

修复后 16 条 Workflow 路由 Eval 命中率为 100%。

## 6. 自动化验收

```bash
sh mvnw -q -Dtest=GeneralCustomerServiceWorkflowTest,Phase9LegacyDependencyBoundaryTest,Phase9WorkflowRoutingBaselineTest test
sh mvnw -q test
sh mvnw -q -DskipTests package
```

本批最终结果：干净构建的 508 个默认测试全部通过，`failures=0`、`errors=0`、`skipped=0`；JAR 构建
通过。JAR 内容审计确认已删除的 Java 类和 Skill reference 没有因旧 `target` 目录而残留。

## 7. 下一步

Phase 9.3 将处理另一条兼容链：`ToolManager -> ToolRegistryCapabilityProvider -> ToolRegistry`。重点是
先让 Handler 和旧 HTTP API 直接依赖 `CapabilityGateway`，再补齐仍由 ToolRegistry 提供的 Local
Provider，最后统一重复的 `capability` / `capability_result` Observation Schema。
