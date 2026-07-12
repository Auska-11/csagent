# 客服Agent-38：通用政策问答 Workflow 迁移说明

## 1. 本次迁移解决什么问题

迁移前，退换货规则、发票、配送和人工转接规则都由 `CustomerServiceSkill` 的知识问答分支统一判断。
这会把“查规则”和“办业务”混在一起：一句包含“退货”的消息，可能是在问七天无理由，也可能是真的
要提交退货申请。

本批次新增 `PolicyQaWorkflow`，只负责通用政策知识问答；保修、订单、退货办理和明确人工请求继续由
各自 Workflow 负责。回答仍复用已经稳定的 `knowledge.search` Capability，不重复实现检索器。

## 2. 新增组件

| 组件 | 职责 |
| --- | --- |
| `skills/policy-qa/manifest.yaml` | 声明通用政策 Skill、Workflow、优先级和最小权限 |
| `skills/policy-qa/SKILL.md` | 描述可回答范围和禁止编造规则 |
| `skills/policy-qa/references/answer-boundary.md` | 约束知识条目归属和通用政策提示 |
| `workflow/policyqa/PolicyQaWorkflow.java` | 路由政策问题、追问主题、检索知识并组织回复 |
| `workflow/policyqa/PolicyQaState.java` | 保存政策问题和步骤 |
| `workflow/policyqa/PolicyQaStep.java` | 定义等待主题、检索中、完成等状态 |

## 3. 负责哪些政策

本 Workflow 只读取以下知识文档：

```text
policy/return_policy.md
policy/invoice_policy.md
logistics/delivery_policy.md
service/handoff_policy.md
```

即使 `knowledge.search` 返回其他高分文档，也不会越权使用。例如发票问题错误返回了保修文档时，
Workflow 会说明没有找到可靠规则，而不是把保修内容拼进发票回答。

## 4. 状态机

```text
START
  -> 明确政策问题 -> SEARCHING -> COMPLETED
  -> “我想咨询政策” -> WAITING_TOPIC

WAITING_TOPIC
  -> 补充“发票什么时候开” -> SEARCHING -> COMPLETED
  -> 明确要求人工            -> HandoffAction
  -> 取消                    -> CANCELLED

SEARCHING
  -> 命中归属知识文档 -> COMPLETED
  -> 没有可靠命中     -> COMPLETED（如实说明）
  -> 技术失败         -> FAILED
```

泛化政策咨询需要追问主题，避免直接用“政策”两个字检索后返回一篇随机文档。

## 5. “查规则”和“办业务”如何区分

| 用户表达 | Workflow |
| --- | --- |
| `超过七天还能退吗` | `PolicyQaWorkflow`，查询退换货政策 |
| `我要退货` | `ReturnApplyWorkflow`，办理退货 |
| `查询退货进度 RET-001` | `ReturnQueryWorkflow` |
| `偏远地区可以配送吗` | `PolicyQaWorkflow`，查询配送政策 |
| `查询订单 ORD-001 到哪了` | `OrderQueryWorkflow` |
| `蓝牙耳机保修多久` | `WarrantyQueryWorkflow` |
| `什么情况可以转人工` | `PolicyQaWorkflow`，查询人工转接规则 |
| `我要人工客服` | 当前 Legacy 升级分支，后续由 `EscalationWorkflow` 接管 |

这组边界通过 `match()` 的主动 `noMatch` 实现。Router 仍然只比较统一分数，不需要增加政策业务特判。

## 6. 为什么“什么情况转人工”不会真的转人工

`GlobalInterruptPolicy` 看到“人工”通常会判断为 `HANDOFF`，这对“我要人工客服”是正确的，但对
“什么情况可以转人工”是错误的。

因此 Policy Workflow 先识别“转人工规则、什么情况转人工、人工客服政策”等知识问句。只有明确
请求人工时才执行 `HandoffAction`；询问规则时继续调用 `knowledge.search`。

这说明全局中断策略提供的是通用默认值，拥有更具体语义的 Workflow 可以在自己的边界内做安全收窄。

## 7. 回答如何避免幻觉

调用链如下：

```text
PolicyQaWorkflow
  -> CapabilityAction(knowledge.search)
  -> ToolRegistryCapabilityProvider / McpCapabilityProvider
  -> List<KnowledgeSearchHit>
  -> 过滤为本 Workflow 拥有的文档
  -> 使用最高相关度 snippet 组织确定性回复
```

Workflow 不直接读取 Markdown，也不调用 LLM 自由补写。没有可靠命中时，会明确说明知识库没有找到
规则，并建议补充问题或联系人工客服。

## 8. 与 WarrantyQueryWorkflow 的关系

保修 Workflow 是此前验证“新增 Skill 不修改核心内核”的扩展示例。通用政策迁移后没有把它合并回
一个大知识 Workflow，而是保留两个清晰所有者：

```text
WarrantyQueryWorkflow -> warranty_policy.md
PolicyQaWorkflow       -> return / invoice / delivery / handoff policy
```

知识检索 Capability 可以复用，但路由边界、回复提示和文档所有权分别维护。

## 9. 测试证据

| 测试 | 主要验证内容 |
| --- | --- |
| `PolicyQaWorkflowTest` | 政策/业务边界、跨轮主题、知识归属、人工规则和人工打断 |
| `PolicyQaWorkflowIntegrationTest` | 真实 Agent、Router、Gateway、KnowledgeTools 和 Markdown 文档链路 |
| `KnowledgeToolsTest` | 退货、发票、配送文档召回和无可靠结果 |
| `KnowledgeRecallEvaluationTest` | 8 个知识库召回评测用例 |
| `NewArchitectureBoundaryTest` | 核心 Runtime、Router、Registry、Gateway 不 import PolicyQaWorkflow |

## 10. 回滚与下一步

`CustomerServiceSkill` 的知识问答分支仍保留作为回滚路径，customer-service Package 也继续拥有
`knowledge.search` 权限。新 Workflow 出现回归时，可以停用独立 Package/Bean 恢复旧路径。

下一批迁移 `UserProfileWorkflow`，重点解决 `user.lookup` 的服务端身份注入和稳定用户输出契约，
并为后续把商品偏好写入从 Legacy 分支迁走建立所有权边界。
