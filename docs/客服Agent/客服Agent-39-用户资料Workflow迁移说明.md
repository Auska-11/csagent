# 客服Agent-39：用户资料 Workflow 迁移说明

## 1. 本次迁移解决什么问题

迁移前，`CustomerServiceSkill` 会把当前会话的 `userId` 直接放进旧 `user_lookup` 工具参数；旧工具还
兼容直接传 token。这个设计适合早期演示，但容易让人误以为“参数里写了谁，就是谁”。

用户资料属于私有数据，安全边界必须是服务端认证结果。本批次新增 `UserProfileWorkflow` 和独立
`LocalUserProfileCapabilityProvider`，并把长期商品偏好写入从 Legacy Skill 一并迁走。

## 2. 新增组件

| 组件 | 职责 |
| --- | --- |
| `skills/user-profile/manifest.yaml` | 声明用户资料 Skill、Workflow 和最小权限 |
| `skills/user-profile/SKILL.md` | 约束认证、token、手机号和偏好读写边界 |
| `skills/user-profile/references/privacy-guidance.md` | 描述手机号脱敏和 Principal 规则 |
| `workflow/userprofile/UserProfileWorkflow.java` | 查询资料、写入/查看偏好和处理多轮追问 |
| `workflow/userprofile/UserProfileState.java` | 独立保存偏好和步骤 |
| `capability/local/LocalUserProfileCapabilityProvider.java` | 只按认证 Principal 查询 UserRepository |
| `capability/api/contracts/UserProfileSummary.java` | 不含 token、手机号脱敏的稳定输出契约 |

## 3. 为什么不再接收 token 或 user_id 参数

旧调用链是：

```text
Workflow
  -> user.lookup({token: state.userId})
  -> UserTools 同时兼容 token 和 userId
```

如果模型、用户输入或外部调用者可以控制这个参数，就可能尝试查询另一个账号。

新调用链是：

```text
Workflow
  -> user.lookup({})
  -> CapabilityGateway 检查 authenticated Principal
  -> LocalUserProfileCapabilityProvider 读取 principal.userId
  -> UserRepository.findByUserId(principal.userId)
```

Provider 会忽略调用参数中伪造的 `token` 和 `user_id`。真正的查询键只有服务端认证 Principal。

## 4. 状态机

```text
START
  -> 查看我的资料 -> LOOKING_UP_PROFILE -> COMPLETED
  -> 我喜欢蓝牙耳机 -> 写入长期偏好 -> COMPLETED
  -> 我有偏好但没说具体商品 -> WAITING_PREFERENCE
  -> 查看我的偏好 -> 读取 UserMemoryStore -> COMPLETED

WAITING_PREFERENCE
  -> 补充“智能手表” -> 写入长期偏好 -> COMPLETED
  -> 要求人工           -> HandoffAction
  -> 取消               -> CANCELLED
```

资料查询和偏好读写都先检查 `Principal.isAuthenticated()`。仅有一个未验证的 userId claim 时，不调用
Capability，也不写长期记忆。

## 5. 手机号与 token 如何保护

`UserProfileSummary` 只包含：

```text
userId、name、phoneMasked
```

完整 token 永远不会进入稳定契约。手机号在 Provider 边界转换为：

```text
13812341234 -> 138****1234
```

远程 MCP 如果返回了完整 `phone`，`McpCapabilityProvider` 恢复契约时会再次脱敏。这是防御性处理，
避免远端实现不一致时把完整号码传入 Workflow。

## 6. 长期偏好所有权

商品咨询迁移时采用了双轨：Legacy Skill 负责写偏好，`ProductAdvisoryWorkflow` 负责读偏好。
本批次完成了第二步：

```text
UserProfileWorkflow       -> 写入/查看长期商品偏好
ProductAdvisoryWorkflow   -> 泛化推荐时读取最近偏好
CustomerServiceSkill      -> 仅保留回滚分支
```

偏好写入使用认证 Principal 的 userId。测试还覆盖了一个容易混淆的表达：

```text
“查看我的偏好” -> 读取
“我喜欢蓝牙耳机” -> 写入
```

读取规则必须优先，否则“查看我的偏好”中的“偏好”会被粗粒度规则误判为一次写入请求。

## 7. Stable Contract 与 MCP

本地旧工具返回 `UserToolData`，远程 MCP 返回 JSON Map。现在两种来源都会转换为
`UserProfileSummary`：

- `ToolRegistryCapabilityProvider` 为手动兼容入口转换旧 DTO。
- `McpCapabilityProvider` 转换远程 JSON 并再次脱敏。
- 生产本地模式由 `LocalUserProfileCapabilityProvider` 直接返回稳定契约。

该 Provider 同时实现 `LegacyCapabilityOverride` 并声明接管 `user.lookup`，所以生产
`ToolRegistryCapabilityProvider` 会自动隐藏旧 `user_lookup`，无需新增过滤分支。

## 8. 测试证据

| 测试 | 主要验证内容 |
| --- | --- |
| `UserProfileWorkflowTest` | 资料查询、认证、偏好读写、跨轮追问和人工打断 |
| `UserProfileWorkflowIntegrationTest` | 真实 Agent、Principal、Gateway、Repository、Memory 和商品推荐链路 |
| `LocalUserProfileCapabilityProviderTest` | 伪造参数无效、认证门禁、手机号脱敏和用户不存在 |
| `ProductAdvisoryWorkflowIntegrationTest` | 新用户 Workflow 写偏好后，商品 Workflow 能读取推荐 |
| `ToolRegistryCapabilityProviderTest` | 旧 UserToolData 转为脱敏稳定契约 |
| `McpCapabilityProviderTest` | 远程用户 JSON 恢复并脱敏 |
| `NewArchitectureBoundaryTest` | 核心 Runtime、Router、Registry、Gateway 不 import UserProfileWorkflow |

## 9. 回滚与限制

旧用户信息和偏好分支仍保留在 `CustomerServiceSkill` 中作回滚。若新 Workflow 出现回归，可以临时
停用独立 Package/Bean。

当前 `DemoAuthenticationService` 仍是学习和演示实现，正式环境必须接入 JWT、Session 或网关身份。
本批次建立的是“后续所有实现都必须遵守的 Principal 边界”，并不把演示 token 当成生产认证方案。

下一批迁移 `EscalationWorkflow` 或全局升级策略，接管明确人工请求、投诉和赔偿纠纷。
