# 客服Agent-21-记忆清除与隐私控制设计

## 1. 为什么要做记忆清除

Agent 具备记忆能力后，不能只考虑“怎么记住”，也要考虑“怎么让用户控制记忆”。

在客服场景里，记忆里可能包含：

- 当前会话正在处理的任务状态。
- 用户长期商品偏好。
- 后续可能扩展的地址、预算、投诉倾向等画像信息。

所以项目需要提供清除能力，避免用户被旧状态影响，也体现基础隐私意识。

## 2. 当前第一版能力

新增两个接口：

```http
DELETE /api/chat/conversations/{conversationId}/memory
DELETE /api/chat/users/{userId}/memory
```

分别对应：

- 清空当前会话记忆：重置当前对话任务状态。
- 清空用户长期记忆：删除跨会话商品偏好。

前端 Memory 面板新增：

- `清空会话`
- `清空长期`

清除接口统一返回：

```json
{
  "scope": "user",
  "targetId": "USER-001",
  "cleared": true,
  "message": "User memory cleared",
  "timestamp": 1783350000000
}
```

## 3. 为什么分成两个清除接口

会话记忆和长期记忆的生命周期不同：

| 记忆类型 | Key | 作用 | 清除影响 |
| --- | --- | --- | --- |
| 会话记忆 | conversationId | 保持当前任务连续性 | 当前对话流程重新开始 |
| 用户长期记忆 | userId | 保存跨会话偏好 | 个性化推荐不再参考旧偏好 |

如果只做一个“清空所有记忆”接口，用户很难控制具体范围。

所以第一版拆成两个接口，边界更清楚。

## 4. 代码位置

```text
ChatApiController
  -> clearConversationMemory()
  -> clearUserMemory()

ConversationStore
  -> clear(conversationId)

UserMemoryStore
  -> clear(userId)

MemoryClearResponse
  -> 统一清除响应结构
```

## 5. 面试表达

如果面试官问：“Agent 记住用户信息会不会有隐私问题？”

可以这样回答：

> 我在第一版长期记忆里只保存商品偏好，不保存敏感信息。同时我把记忆分成会话记忆和用户长期记忆，并提供两个清除接口：一个按 conversationId 清空当前会话状态，一个按 userId 清空长期用户偏好。前端 Memory 面板也能直接触发清除。这样 Agent 既能利用记忆提升体验，也保留了用户可控和调试可控的边界。

## 6. 后续优化

- 增加记忆写入前的用户确认。
- 给长期记忆增加过期时间。
- 记录记忆来源，说明某条偏好来自哪次会话。
- 增加敏感信息过滤，禁止写入手机号、身份证等隐私字段。
- 持久化后增加审计日志。
