# 客服Agent-20-长期用户偏好记忆设计

## 1. 为什么要做长期记忆

前面已经完成了会话级记忆：

- 短期记忆：最近几轮消息。
- 任务记忆：当前意图、槽位、订单号、退货原因。
- 摘要记忆：长对话压缩后的摘要。

但这些主要解决的是“当前会话里别忘事”。

长期记忆解决的是另一类问题：用户跨会话的偏好。

例如：

```text
用户：我喜欢蓝牙耳机
Agent：好的，我记住了……

用户新开一个会话：给我推荐一下
Agent：优先参考蓝牙耳机偏好进行商品搜索
```

这个能力能体现 Agent 不只是一次性问答，而是能逐步形成用户画像。

## 2. 当前第一版范围

第一版只做商品偏好记忆：

- 识别用户偏好表达，例如“我喜欢蓝牙耳机”“以后推荐智能手表”。
- 写入用户长期记忆 `UserMemoryStore`。
- 当用户泛化地说“给我推荐一下”时，优先读取最近的商品偏好。
- Trace 记录：
  - `user_memory_updated`
  - `user_memory_used`
- 前端 Memory 面板展示 User Long-Term Memory。
- 提供查询接口：
  - `GET /api/chat/users/{userId}/memory`

暂时不做：

- 数据库持久化。
- 复杂用户画像。
- 地址、预算、投诉倾向等多维偏好。
- LLM 自动抽取长期记忆。

## 3. 类设计

新增：

```text
memory/UserMemoryStore.java
memory/UserMemorySnapshot.java
```

`UserMemoryStore` 当前用 `ConcurrentHashMap` 保存内存数据：

```text
userId -> MutableUserMemory
```

`MutableUserMemory` 中保存：

- `productPreferences`
- `version`
- `updatedAt`

对外只返回 `UserMemorySnapshot`，避免外部代码直接修改内部集合。

## 4. 为什么不直接放进 ConversationState

`ConversationState` 属于会话状态，它的 key 是 `conversationId`。

长期用户记忆属于用户画像，它的 key 是 `userId`。

如果把用户长期偏好塞进 `ConversationState`，会有两个问题：

- 新开会话后偏好可能丢失。
- 会话状态和用户画像职责混在一起，后续持久化会困难。

所以当前设计是：

```text
ConversationState -> 保存当前会话任务状态
UserMemoryStore   -> 保存跨会话用户偏好
```

## 5. 执行流程

### 5.1 写入偏好

```text
用户：我喜欢蓝牙耳机
CustomerServiceSkill
  -> intentRules.isPreferenceMemoryMessage()
  -> slotExtractor.extractProductPreference()
  -> userMemoryStore.rememberProductPreference()
  -> Trace: user_memory_updated
  -> FinalAnswerAction
```

### 5.2 使用偏好

```text
用户：给我推荐一下
CustomerServiceSkill
  -> 当前消息没有明确商品关键词
  -> userMemoryStore.getSnapshot(userId)
  -> 取最近商品偏好“蓝牙耳机”
  -> Trace: user_memory_used
  -> CapabilityAction(product_search, keyword=蓝牙耳机)
```

## 6. 面试表达

如果面试官问：“你的长期记忆怎么做？”

可以这样回答：

> 我把记忆分成会话记忆和用户长期记忆。会话记忆放在 ConversationState 里，负责当前任务状态，比如订单号、退货原因、短期消息窗口和摘要。用户长期记忆单独放在 UserMemoryStore 里，按 userId 保存跨会话偏好。第一版先做商品偏好，比如用户说“我喜欢蓝牙耳机”，系统会记录到长期记忆；后面用户只说“给我推荐一下”，Agent 会读取偏好并优先搜索蓝牙耳机。这样职责边界比较清楚，后续把 UserMemoryStore 换成数据库持久化也比较自然。

如果面试官问：“为什么第一版不用数据库？”

可以这样回答：

> 当前项目重点是把 Agent 记忆机制的边界和使用链路讲清楚。第一版用内存仓库可以保证测试稳定、实现轻量，也不会引入数据库配置成本。后续如果要生产化，只需要把 UserMemoryStore 的内部实现替换成 MySQL 或 Redis，上层 Skill 和 Controller 不需要大改。

## 7. 后续优化

- 把 `UserMemoryStore` 持久化到 MySQL。
- 增加清除长期记忆接口。
- 增加预算、品牌、常用地址等画像字段。
- 用 LLM 从对话中抽取更自然的长期偏好。
- 给长期记忆增加过期时间和用户确认机制。
