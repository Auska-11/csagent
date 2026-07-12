# 客服Agent-17-多轮记忆管理升级设计

## 1. 为什么要升级记忆

前面我们已经完成了多轮会话第一版：`ConversationStore` 可以按
`(tenantId, userId, conversationId)` 保存状态，`ConversationState` 可以记录当前意图、槽位和最近消息。

但如果只把所有历史消息一直塞进 `messages`，长对话会有两个问题：

- Prompt 越来越长，模型调用成本和延迟都会变高。
- 旧消息和当前任务混在一起，Agent 更容易被无关历史干扰。

所以这一版把记忆拆成三层：

| 记忆层 | 保存内容 | 作用 |
| --- | --- | --- |
| 短期记忆 | 最近几条用户/助手消息 | 保留当前上下文 |
| 任务记忆 | activeIntent、pendingSlot、订单号、退货原因等槽位 | 保证多轮任务不中断 |
| 摘要记忆 | 较早消息压缩后的摘要 | 防止长对话无限增长 |

这套设计的重点不是“记住一切”，而是把不同用途的上下文分开管理。

## 2. 当前实现

本次新增和修改的核心文件：

```text
src/main/java/com/smartcs/smart_cs_agent/agent/context/ConversationState.java
src/main/java/com/smartcs/smart_cs_agent/agent/context/ConversationMemorySnapshot.java
src/main/java/com/smartcs/smart_cs_agent/agent/context/ConversationStore.java
src/main/java/com/smartcs/smart_cs_agent/agent/AgentRuntime.java
src/main/java/com/smartcs/smart_cs_agent/memory/MemorySafetyProperties.java
src/main/java/com/smartcs/smart_cs_agent/security/SensitiveTextRedactor.java
src/main/java/com/smartcs/smart_cs_agent/controller/ChatApiController.java
src/main/resources/static/index.html
```

新增测试：

```text
src/test/java/com/smartcs/smart_cs_agent/agent/ConversationStateMemoryTest.java
```

## 3. 短期记忆

短期记忆复用 `ConversationState.messages`，但它不再表示完整历史，而是表示“最近消息窗口”。

当前规则：

```java
private static final int SHORT_TERM_MESSAGE_LIMIT = 8;
```

每当新增用户消息或助手消息时，都会调用统一的 `addMessage()`：

```java
private void addMessage(String role, String content) {
    messages.add(Map.of("role", role, "content", content == null ? "" : content));
    totalMessageCount++;
    compressMemoryIfNeeded();
}
```

这样做的好处是：

- 所有消息入口都统一。
- 不容易忘记触发压缩。
- 测试可以稳定验证短期消息不会无限增长。

## 4. 任务记忆

任务记忆来自 `activeIntent`、`pendingSlot` 和 `taskState`。

例如退货流程：

```text
activeIntent = return_apply
pendingSlot = return_reason
order_id = ORD-004
```

这一层的价值是：用户第二轮只说 `ORD-004` 或 `质量问题` 时，Agent 能知道它是在补充哪个字段。

本次新增了 `getTaskMemory()`，会过滤掉 Runtime 内部临时字段：

```text
last_llm_purpose
response_generated
intent_handled
last_capability
last_capability_result
```

这些字段对代码执行有用，但不适合作为对外展示的“任务记忆”。

## 5. 摘要记忆

当短期消息超过窗口上限时，较早消息会被压缩进 `summaryMemory`。

第一版没有使用 LLM 摘要，而是使用规则摘要：

- 不依赖外部模型，测试稳定。
- 行为可解释，新手更容易读懂。
- 后续可以替换成 LLM Summary，不影响外层接口。

摘要示例：

```text
第 1 次压缩：已归档 1 条较早消息。关键片段：用户：我要退货
```

Phase 8 第六批又给摘要增加了安全边界：

- 默认最多保留 2000 个 Unicode 字符，可通过 `smartcs.memory.summary-max-length` 调整。
- 新摘要生成时，先遮蔽手机号、邮箱、证件号、长数字和常见凭据，再执行长度限制。
- 超过上限时从旧内容一侧淘汰，优先保留最近压缩出的上下文。
- 从 SessionStore 恢复旧快照时会再次脱敏和截断，历史数据不能绕过新策略。

这里限制的是 `summaryMemory`，不是最近消息窗口中单条原始消息的长度。最近消息、Trace 和工具参数的
统一敏感信息治理仍是 Phase 8 后续任务，不能因为摘要已经脱敏就认为整个系统都完成了隐私收口。

后续如果要升级，可以新增 `MemoryCompressor`：

```text
RuleBasedMemoryCompressor
LlmMemoryCompressor
```

当前先不抽象，是为了避免过早设计。

## 6. Runtime 如何使用记忆

`AgentRuntime` 在每轮开始时记录：

```text
memory_loaded
```

Trace 数据包括：

- activeIntent
- pendingSlot
- shortTermMessageCount
- totalMessageCount
- summaryVersion
- hasSummary

如果助手回复写入后触发了压缩，会记录：

```text
memory_compressed
```

这样前端 Trace 可以看见“什么时候读取了记忆，什么时候发生了压缩”。

LLM 调用时，如果存在摘要记忆，会把摘要作为额外 `SystemMessage` 加入上下文：

```text
以下是较早会话的压缩摘要，请作为上下文参考，不要逐字复述给用户：
...
```

这样长对话压缩后，模型仍然能看到较早上下文。

## 7. Memory API

新增接口：

```http
GET /api/chat/conversations/{conversationId}/memory
```

当前接口会使用认证 Principal 组装完整 `ConversationKey`，不能只凭一个猜到的 `conversationId`
读取其他用户的会话。

返回 `ConversationMemorySnapshot`：

```json
{
  "conversationId": "conv-001",
  "userId": "USER-001",
  "activeIntent": "return_apply",
  "pendingSlot": "return_reason",
  "taskMemory": {
    "activeIntent": "return_apply",
    "pendingSlot": "return_reason",
    "order_id": "ORD-004"
  },
  "shortTermMessages": [
    {"role": "user", "content": "ORD-004"}
  ],
  "summaryMemory": "",
  "totalMessageCount": 3,
  "shortTermMessageLimit": 8,
  "summaryVersion": 0,
  "summaryUpdatedAt": 0
}
```

为什么不直接返回 `ConversationState`？

因为 `ConversationState` 是内部状态对象，里面有 Runtime 临时字段。对外返回专门的 Snapshot，可以让接口更稳定。

## 8. 前端展示

前端 Agent Inspector 新增 `Memory` Tab。

当前展示：

- Intent
- Pending
- Messages
- Short-Term
- Summary Version
- Task Memory
- Summary Memory
- Short-Term Messages

这个面板适合面试演示：

1. 发送 `我要退货`。
2. 打开 Memory，看 `activeIntent = return_apply`、`pendingSlot = order_id`。
3. 发送 `ORD-004`。
4. 再看 Memory，`pendingSlot` 会变成 `return_reason`，`order_id` 会被保存。
5. 长对话后可以看到 Summary Memory 发生更新。

## 9. 面试表达

如果面试官问：“你怎么做多轮记忆？”

可以这样回答：

> 我没有简单把所有历史消息都塞进 prompt，而是把记忆拆成三层。短期记忆保存最近 8 条消息，用于当前上下文；任务记忆保存 activeIntent、pendingSlot 和业务槽位，保证退货、订单查询这类多轮任务不断；摘要记忆负责压缩较早消息，防止长对话 prompt 无限增长。Runtime 每轮会记录 memory_loaded，发生压缩时记录 memory_compressed，前端 Memory 面板可以直接查看当前会话记忆。

如果面试官问：“为什么摘要不用 LLM？”

可以这样回答：

> 第一版先用规则摘要，因为它稳定、可测试、不依赖外部模型，适合保护主链路。这个设计保留了 summaryMemory 的结构，后续可以把规则压缩器替换成 LLM 压缩器，不影响 Controller、Runtime 和前端接口。

## 10. 后续优化

已经完成的后续能力：

- 支持清空当前会话记忆和用户长期偏好。
- 支持跨会话商品偏好，并增加 TTL、容量、长度和脱敏边界。
- `ConversationStore` 已通过 `SessionStore` SPI 支持 memory / JDBC 双模式和重启恢复。

仍可继续优化：

- 使用 LLM 生成结构化摘要，并对摘要忠实度、遗漏率和注入风险增加评测。
- 限制最近消息窗口中单条消息的长度，并统一治理 Trace、Prompt 和工具参数中的敏感内容。
- 为摘要增加来源、保留期限和用户可见的删除说明。
- 对姓名、地址等需要业务上下文判断的敏感字段引入更可靠的分类器或 DLP 服务。

长期偏好的本批实现细节见 `客服Agent-47-长期记忆TTL容量与脱敏说明.md`。

当前这一版的目标是先把“三级记忆结构”和“可观测 Memory 面板”做扎实。
