# 客服Agent-21-记忆清除与隐私控制设计

## 1. 为什么要做记忆清除

Agent 具备记忆能力后，不能只考虑“怎么记住”，还要考虑：

- 谁可以查看记忆。
- 用户能否删除记忆。
- 数据保留多久。
- 写入前是否会过滤高风险内容。
- 删除、过期和容量淘汰分别意味着什么。

当前项目把会话记忆和用户长期偏好分开管理，因此查询和清除也必须分开授权、分开执行。

## 2. 当前查询与清除接口

```http
GET    /api/chat/conversations/{conversationId}/memory
DELETE /api/chat/conversations/{conversationId}/memory
GET    /api/chat/users/{userId}/memory
DELETE /api/chat/users/{userId}/memory
```

含义分别是：

- 查看或清空当前认证用户拥有的指定会话。
- 查看或清空当前认证用户的跨会话商品偏好。

会话接口会使用认证 Principal 组装完整的：

```text
(tenantId, userId, conversationId)
```

因此不能只凭猜到的 `conversationId` 读取或删除其他用户会话。用户长期记忆接口也会校验路径中的
`userId` 与认证 Principal 一致，而不是直接相信 URL 参数。

清除接口统一返回 `MemoryClearResponse`：

```json
{
  "scope": "user",
  "targetId": "USER-001",
  "cleared": true,
  "message": "User memory cleared",
  "timestamp": 1783350000000
}
```

## 3. 为什么分成两个清除范围

| 记忆类型 | 身份 Key | 清除后的影响 |
| --- | --- | --- |
| 会话记忆 | tenantId + userId + conversationId | 当前 Workflow、槽位、消息窗口和摘要被删除 |
| 用户长期记忆 | userId | 后续泛化推荐不再参考已保存商品偏好 |

只提供“清空所有记忆”会让用户无法精确控制影响范围。例如用户可能只想重新开始当前退货流程，并不想
删除跨会话商品偏好；也可能只想取消个性化推荐，而保留当前会话。

## 4. 自动隐私边界

除了显式删除，本批还增加了自动边界：

- 长期偏好默认 30 天 TTL，重复表达会刷新最后表达时间。
- 达到用户或偏好容量上限时，淘汰较久未访问或未表达的数据。
- 单条偏好默认最多 80 个 Unicode 字符。
- 会话规则摘要默认最多 2000 个 Unicode 字符，超限保留最近内容。
- 长期偏好和会话摘要写入前遮蔽常见凭据、邮箱、手机号、身份证和长数字。
- 恢复旧会话快照时会再次脱敏和限制摘要长度。
- 脱敏后没有实际商品内容的偏好会被拒绝。

TTL、容量淘汰和用户主动删除不是同一个概念：

- TTL 是读写时惰性检查，不保证到期瞬间物理删除。
- 容量淘汰是资源保护策略，不代表用户主动撤回授权。
- DELETE 是当前用户明确发起的删除操作。

## 5. 代码位置

```text
ChatApiController
  -> getConversationMemory()
  -> clearConversationMemory()
  -> getUserMemory()
  -> clearUserMemory()

ConversationStore
  -> get(ConversationKey)
  -> clear(ConversationKey)

UserMemoryStore
  -> getSnapshot(userId)
  -> clear(userId)

SensitiveTextRedactor
  -> 写入长期偏好和会话摘要前脱敏

MemoryClearResponse
  -> 统一清除响应结构
```

`ConversationStore.clear()` 会进入与会话执行相同的本地锁，并使用 SessionStore revision 删除，避免一轮
Workflow 正在保存时和清除操作互相覆盖。`UserMemoryStore.clear()` 使用同步入口删除当前进程中的用户偏好。

## 6. 当前安全边界

已经具备：

- 会话所有权校验。
- 用户长期记忆身份校验。
- 显式查询和清除接口。
- TTL、容量、长度和确定性高风险格式脱敏。
- 安全写入结果，避免 Workflow 继续传播原始偏好。

仍未达到生产完整标准：

- HTTP 已统一使用 Spring Security Bearer JWT，Memory 只信任经过签名、issuer、audience、时间、subject
  和 tenant 校验的 `AuthenticatedPrincipal`；本地 Demo 签发器仍不等于真实 OIDC 登录系统。
- Monitor、Eval、Tool 和 MCP HTTP 接口已接入角色权限；远程 MCP 的签名用户委托尚未实现。
- 长期偏好仍在进程内，服务重启后会自然丢失，多实例之间也不共享。
- 没有记录“谁在何时查看或删除了哪类记忆”的持久化审计日志。
- 没有单条偏好删除、个人数据导出和保留策略版本记录。
- 姓名、地址等上下文型敏感字段尚未自动识别。
- Trace、原始 Prompt、最近消息和工具参数的统一脱敏仍是 Phase 8 后续任务。

## 7. 面试表达

可以这样回答：

> 我把会话记忆和用户长期记忆分成两个生命周期，并提供独立查询和删除接口。会话操作基于
> tenantId、userId、conversationId 的完整所有权边界，长期记忆接口也校验认证 Principal。长期偏好
> 还有 TTL、容量和写入前脱敏，摘要在生成和旧快照恢复时都会重新脱敏。当前版本已经解决基础的用户
> 可控和资源边界，并通过统一 JWT Principal 阻止请求体 userId 冒充。但我不会把它描述成完整生产
> 隐私体系，因为真实 OIDC/令牌撤销、访问审计、上下文型敏感信息识别和全链路 Trace 脱敏仍需完成。

## 8. 后续优化

- 用独立 IdP 的 OIDC/非对称 JWT 替换本地 Demo 签发器，并增加令牌撤销、轮换和账户停用联动。
- 把长期记忆抽成持久化 SPI，支持数据库删除、到期索引和多实例一致性。
- 增加单条偏好撤销、数据导出、来源说明和用户确认。
- 增加删除审计，但审计记录本身不能保存被删除的敏感正文。
- 引入结构化字段策略或 DLP 服务识别姓名、地址等上下文型敏感内容。
- 对 Trace、Prompt、最近消息、工具参数和 MCP 审计做统一脱敏测试。

TTL、容量和脱敏的具体实现见 `客服Agent-47-长期记忆TTL容量与脱敏说明.md`。
JWT Principal、角色矩阵和 MCP 服务身份边界见 `客服Agent-48-JWT统一认证与角色权限说明.md`。
