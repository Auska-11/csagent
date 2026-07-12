# 客服Agent-20-长期用户偏好记忆设计

## 1. 为什么要做长期记忆

会话记忆主要解决“当前对话里别忘事”：

- 短期记忆保存最近几轮消息。
- 任务记忆保存当前 Workflow、步骤和槽位。
- 摘要记忆保存较早对话的规则摘要。

长期记忆解决的是另一类问题：用户换了 `conversationId` 以后，Agent 是否还知道他主动表达过的偏好。

```text
会话 A：我喜欢蓝牙耳机
Agent：好的，我记住了

会话 B：给我推荐一下
Agent：读取该用户最近的商品偏好，优先搜索蓝牙耳机
```

长期记忆不是“把所有聊天永久保存”。当前项目只保存明确的商品或品类偏好，并给它设置生命周期、
容量、长度、脱敏和清除边界。

## 2. 当前实现范围

已经实现：

- `UserProfileWorkflow` 负责新架构主链路中的偏好写入和查看。
- `ProductAdvisoryWorkflow` 在泛化推荐时读取最近商品偏好。
- Phase 9 前保留的 Legacy `CustomerServiceSkill` 使用同一个安全写入入口。
- `GET /api/chat/users/{userId}/memory` 查看认证用户的长期偏好。
- `DELETE /api/chat/users/{userId}/memory` 清空认证用户的长期偏好。
- Trace 记录 `user_memory_updated` 和 `user_memory_used`，其中只使用已脱敏的存储文本。
- 默认 30 天 TTL；重复表达同一偏好会刷新最后表达时间。
- 限制用户总量、每用户偏好数、单条 Unicode 长度。
- 写入前遮蔽常见凭据、邮箱、中国手机号、身份证号和 12 至 19 位长数字。
- 脱敏后只剩敏感标签、占位符和连接词时拒绝保存。

暂未实现：

- 长期偏好的 MySQL、Redis 或远程画像服务持久化。
- 品牌、预算、尺码、地址等结构化多维画像。
- 每条偏好的来源会话、证据、置信度和用户确认记录。
- 姓名、详细地址等需要上下文判断的敏感字段识别。
- 后台定时清理任务。

## 3. 核心类和职责

| 类 | 职责 |
| --- | --- |
| `UserMemoryStore` | 管理长期偏好的写入、读取、TTL、容量和清除 |
| `UserMemorySnapshot` | 向 Workflow、Controller 返回不可直接修改的快照 |
| `UserMemoryWriteResult` | 返回 accepted、added 和已安全处理的 storedPreference |
| `MemorySafetyProperties` | 承载 TTL、容量和文本长度配置 |
| `SensitiveTextRedactor` | 执行确定性高风险格式脱敏和 Unicode 截断 |

`UserMemoryStore` 仍是进程内实现。外层结构是带访问顺序的 `LinkedHashMap`：

```text
userId -> MutableUserMemory
```

内部每个用户使用另一个 `LinkedHashMap`：

```text
safePreference -> lastSeenAt
```

这里的 `lastSeenAt` 是用户最后一次表达该偏好的时间，不是 Agent 最后一次读取偏好的时间。

## 4. 为什么不直接放进 ConversationState

两者的身份和生命周期不同：

| 状态 | Key | 生命周期 | 作用 |
| --- | --- | --- | --- |
| `ConversationState` | tenantId + userId + conversationId | 一段会话 | 当前 Workflow、槽位、消息窗口和摘要 |
| `UserMemoryStore` | userId | 跨多个会话 | 用户主动表达的商品偏好 |

如果把长期偏好塞进 `ConversationState`：

- 用户新开会话后不一定能读到旧偏好。
- 会话恢复、用户画像和隐私清除会混成一个职责。
- 以后接画像数据库时，Runtime 和所有 Workflow 都可能被迫跟着修改。

因此当前边界是：

```text
ConversationState -> 当前会话状态
UserMemoryStore   -> 跨会话用户偏好
```

## 5. 安全写入流程

新架构主链路如下：

```text
用户表达偏好
  -> UserProfileWorkflow 提取商品或品类
  -> UserMemoryStore.rememberProductPreferenceSafely()
  -> SensitiveTextRedactor 脱敏、压缩空白、限制 Unicode 长度
  -> 判断脱敏后是否还存在实际内容
  -> 清理已过期偏好
  -> 刷新重复偏好的 TTL，或写入新偏好
  -> 执行偏好级 / 用户级容量淘汰
  -> 返回 UserMemoryWriteResult
  -> Workflow 只把 storedPreference 写入状态、Trace 和回复
```

为什么要返回 `UserMemoryWriteResult`，而不是只返回 boolean？

只返回 boolean 无法告诉调用方“最终安全存储的文本是什么”。调用方很容易继续使用原始 `preference`，
把手机号或凭据写进 Trace、任务状态或回复。现在返回值明确区分：

- `accepted=false`：输入为空，或脱敏后没有可保存的商品内容。
- `accepted=true, added=true`：新增了安全偏好。
- `accepted=true, added=false`：偏好已存在，本次只刷新 TTL 和最近表达顺序。
- `storedPreference`：唯一允许调用方继续传播的偏好文本。

原来的 `rememberProductPreference()` boolean 方法仍保留，用于旧测试和兼容调用；新业务代码应优先使用
`rememberProductPreferenceSafely()`。

## 6. 读取和推荐流程

当用户只说“给我推荐一下”，当前消息没有明确商品关键词时：

```text
ProductAdvisoryWorkflow
  -> userMemoryStore.getSnapshot(userId)
  -> 读时清理当前用户的过期偏好
  -> 取列表最后一项，也就是最近表达的偏好
  -> Trace: user_memory_used
  -> CapabilityAction(product.search, keyword=偏好)
```

读取快照也会让该用户成为“最近访问用户”，因此用户总量达到上限时，较活跃用户更不容易被淘汰。

## 7. TTL 与容量语义

默认配置：

| 配置 | 默认值 | 含义 |
| --- | ---: | --- |
| `preference-ttl-ms` | `2592000000` | 偏好最后表达后保留 30 天 |
| `max-users` | `10000` | 单进程最多保留的用户数 |
| `max-preferences-per-user` | `20` | 每用户最多偏好数 |
| `max-preference-length` | `80` | 单条偏好最多 Unicode 字符数 |
| `summary-max-length` | `2000` | 会话规则摘要最多 Unicode 字符数 |

TTL 是偏好级 TTL，不是整个用户快照共用一个时间：

```text
蓝牙耳机 lastSeenAt = T1
智能手表 lastSeenAt = T2
```

重复表达“蓝牙耳机”时会把它移动到列表末尾，并把 `lastSeenAt` 更新为当前时间。这样用户最近再次确认的
偏好不会按第一次写入时间过期。

容量有两层：

- 用户层按读写访问顺序淘汰最久未访问用户。
- 偏好层按最后表达顺序淘汰最久未表达偏好。

TTL 采用惰性清理：读或写发生时才检查并删除过期项，不存在后台线程在到期瞬间删除数据。这个实现
适合当前 Demo 和确定性测试，但不等于生产级过期调度。

## 8. 脱敏边界

当前确定性规则会遮蔽：

- `token`、`password`、`secret`、API key、access token 等常见英文凭据。
- `密码`、`口令`、`密钥`、`令牌` 等常见中文凭据标签。
- 邮箱。
- 中国大陆 11 位手机号。
- 18 位身份证号。
- 12 至 19 位连续长数字，例如常见银行卡号格式。

例如：

```text
输入：蓝牙耳机，手机号 13800138000，邮箱 buyer@example.com
保存：蓝牙耳机，手机号 [REDACTED]，邮箱 [REDACTED]
```

如果输入是：

```text
我的手机号是 13800138000
```

脱敏后没有商品或品类内容，Store 会拒绝保存，而不是留下“我的手机号是 [REDACTED]”这种无效记忆。

正则脱敏不是完整 DLP。姓名、地址、昵称和上下文型账号可能没有固定格式，当前不会盲目删除，避免把
正常商品名称误判为隐私字段。生产环境需要结合结构化表单、实体识别、分类器或专业 DLP 服务。

## 9. 面试表达

可以这样说明：

> 我把会话状态和跨会话用户记忆分开。长期偏好按 userId 管理，写入时先做高风险格式脱敏，再执行
> Unicode 长度限制和有效内容判断。每条偏好有基于最后表达时间的 TTL，重复表达会刷新 TTL；内存实现
> 还设置用户总量和每用户偏好上限。Workflow 只能使用安全写入结果返回的 storedPreference，避免原始
> 输入再次进入 Trace 或回复。当前实现仍是进程内仓库，生产化下一步是抽成持久化 SPI，并补充用户确认、
> 来源审计和更可靠的上下文敏感信息识别。

## 10. 当前限制和后续方向

- 服务重启后，长期偏好会丢失；JDBC `SessionStore` 只持久化会话状态，不持久化 `UserMemoryStore`。
- 多实例之间不共享长期偏好，每个实例有自己的容量和 TTL 状态。
- 每次写入会扫描当前内存用户并清理过期项，受 `max-users` 约束但仍不适合无限规模。
- 惰性 TTL 不保证到期瞬间物理删除。
- 当前没有“记忆来源、用户确认、撤销单条偏好、导出个人数据”和审计日志。
- 当前脱敏规则不自动识别姓名和地址。
- 用户最近消息、Prompt、完整 Trace 和 MCP 审计的统一脱敏仍属于 Phase 8 后续任务。

本批更详细的代码、配置、算法和测试说明见 `客服Agent-47-长期记忆TTL容量与脱敏说明.md`。
