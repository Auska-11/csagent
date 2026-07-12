# 客服Agent-47-长期记忆 TTL、容量与脱敏说明

## 1. 本批要解决什么问题

长期记忆第一版已经能保存商品偏好，但原实现只有一个无边界的内存 Map：

- 数据不会过期。
- 用户数和每用户偏好数可以无限增长。
- 单条文本没有长度限制。
- 手机号、邮箱、身份证和凭据可能被写进记忆。
- 会话摘要会持续累加。
- Workflow 可能在 Store 脱敏后，仍把原始输入写进 Trace 或回复。

Phase 8 第六批的目标是给现有记忆能力补上最小但真实的安全边界：

```text
长期偏好：TTL + 两级容量 + 单条长度 + 写入前脱敏
会话摘要：配置化上限 + 写入脱敏 + 旧快照恢复脱敏
调用链：只允许传播 Store 返回的安全文本
```

这不是完整生产隐私体系。本批解决的是 Memory 自己的生命周期和写入边界，真实认证、角色权限、
全链路 Trace / Prompt / MCP 审计脱敏仍是后续任务。

## 2. 核心文件

新增：

```text
memory/MemorySafetyProperties.java
memory/UserMemoryWriteResult.java
security/SensitiveTextRedactor.java
security/SensitiveTextRedactorTest.java
memory/MemorySafetyPropertiesTest.java
```

主要修改：

```text
memory/UserMemoryStore.java
agent/context/ConversationState.java
agent/context/ConversationStore.java
agent/context/WorkflowStateMigrationRegistry.java
workflow/userprofile/UserProfileWorkflow.java
skill/CustomerServiceSkill.java
```

配置同步到：

```text
src/main/resources/application.yml
docker-compose.yml
.env.example
```

`AgentRuntime`、`WorkflowRouter` 和 `DefaultCapabilityGateway` 没有新增商品偏好或敏感字段特判。安全规则
留在 Memory、Security 和会话恢复边界，避免中心编排层继续膨胀。

## 3. 配置项

配置前缀是：

```text
smartcs.memory
```

| YAML 配置 | 环境变量 | 默认值 | 含义 |
| --- | --- | ---: | --- |
| `preference-ttl-ms` | `SMARTCS_MEMORY_PREFERENCE_TTL_MS` | `2592000000` | 偏好最后表达后保留 30 天 |
| `max-users` | `SMARTCS_MEMORY_MAX_USERS` | `10000` | 单进程最多用户数 |
| `max-preferences-per-user` | `SMARTCS_MEMORY_MAX_PREFERENCES_PER_USER` | `20` | 每用户最多偏好数 |
| `max-preference-length` | `SMARTCS_MEMORY_MAX_PREFERENCE_LENGTH` | `80` | 单条偏好最大 Unicode 字符数 |
| `summary-max-length` | `SMARTCS_MEMORY_SUMMARY_MAX_LENGTH` | `2000` | 会话规则摘要最大 Unicode 字符数 |

`MemorySafetyProperties` 的 setter 会拒绝 0 和负数。原因是：

- TTL 为 0 会让数据立即失效。
- 容量为 0 无法形成有效 Store。
- 长度为 0 会让所有内容变成空文本。

配置错误时应尽早失败，而不是让服务带着模糊语义继续运行。

YAML 示例：

```yaml
smartcs:
  memory:
    preference-ttl-ms: ${SMARTCS_MEMORY_PREFERENCE_TTL_MS:2592000000}
    max-users: ${SMARTCS_MEMORY_MAX_USERS:10000}
    max-preferences-per-user: ${SMARTCS_MEMORY_MAX_PREFERENCES_PER_USER:20}
    max-preference-length: ${SMARTCS_MEMORY_MAX_PREFERENCE_LENGTH:80}
    summary-max-length: ${SMARTCS_MEMORY_SUMMARY_MAX_LENGTH:2000}
```

## 4. 长期偏好的完整写入顺序

`rememberProductPreferenceSafely()` 的顺序很重要：

```text
1. 校验并 trim userId
2. 拒绝 null / 空白偏好
3. 遮蔽高风险格式
4. 把换行和连续空白压成单个空格
5. 按 Unicode code point 限制单条长度，且不从中间切开 `[REDACTED]`
6. 判断脱敏后是否还存在实际商品内容
7. 清理已经过期的用户偏好
8. 获取或创建该用户记忆
9. 新用户写入前执行用户容量淘汰
10. 清理当前用户过期偏好
11. 刷新重复偏好，或新增偏好
12. 执行每用户偏好容量淘汰
13. 返回只包含安全文本的 UserMemoryWriteResult
```

为什么必须先脱敏再截断？

如果先截断，敏感值可能被切成正则无法识别的半段，后续脱敏就会漏掉。当前实现先扫描完整输入，再对
脱敏后的结果做长度限制。

为什么用 Unicode code point，而不是 Java `String.length()`？

`String.length()` 统计 UTF-16 code unit，一个 emoji 可能算 2。按 code point 截断可以避免从中间切坏
emoji 或其他补充平面字符。

如果管理员把长度上限配置得比 `[REDACTED]` 还短，截断器会把放不下的占位符整体移除，不会留下
`[RED` 这种半截英文。Store 同时保留“截断前是否发生过脱敏”的布尔信息，因此半截或被移除的占位符
也不能绕过敏感信息-only 判断。

## 5. TTL 是怎样工作的

每条偏好保存：

```text
safePreference -> lastSeenAt
```

例如：

```text
蓝牙耳机 -> 2026-07-12 10:00
智能手表 -> 2026-07-20 09:30
```

过期判断基于：

```text
now - lastSeenAt >= preferenceTtlMs
```

重复表达同一个偏好时，Store 会：

1. 从内部顺序 Map 中删除旧项。
2. 用当前时间重新放到末尾。
3. 返回 `added=false`，表示不是新的语义偏好。
4. 更新 `updatedAt`。

因此重复表达会同时刷新 TTL 和最近表达顺序。

### 5.1 为什么采用惰性清理

当前没有后台定时任务。清理发生在：

- 写入任意偏好前：扫描当前用户 Map，删除已经没有有效偏好的用户。
- 读取某个用户快照时：清理该用户已经过期的偏好。

优点：

- 没有额外线程和调度配置。
- 单元测试可以注入 `Clock`，不需要真的 `sleep`。
- Demo 行为更确定、更容易理解。

限制：

- 到期数据不会在到期瞬间自动从内存消失。
- 如果服务长期没有任何相关读写，过期对象会继续占用内存。
- 每次写入扫描所有当前用户，复杂度受 `max-users` 限制，但不适合无限规模。

生产持久化实现应优先使用数据库过期索引、Redis TTL 或独立清理任务。

## 6. 两级容量淘汰

### 6.1 用户级访问顺序

外层 Map 使用：

```java
new LinkedHashMap<>(16, 0.75f, true)
```

`accessOrder=true` 表示成功读取或写入一个用户后，该用户会移动到末尾。达到 `max-users` 且需要创建
新用户时，Map 最前面的用户就是最久未访问者，会被淘汰。

```text
最久未访问                           最近访问
USER-002 -> USER-001 -> USER-003
   ^
   达到上限时优先淘汰
```

这是单进程、近似 LRU 的资源保护，不是用户数据保留承诺。多个应用实例各自维护自己的顺序。

### 6.2 偏好级最后表达顺序

每个用户内部的 `LinkedHashMap` 不会因推荐读取而调整偏好顺序。只有用户再次表达某个偏好时，Store 才
执行 remove + put，把它移动到末尾。

```text
最久未表达                           最近表达
数据线 -> 蓝牙耳机 -> 智能手表
   ^
   超过 max-preferences-per-user 时淘汰
```

所以更准确的说法是：

- 用户层按访问顺序淘汰。
- 偏好层按最后表达顺序淘汰。

不是所有层都按读取次数计算热度。

## 7. 脱敏规则和顺序

`SensitiveTextRedactor.redact()` 当前按以下顺序处理：

1. 常见英文和中文凭据。
2. 邮箱。
3. 18 位中国身份证号。
4. 中国大陆 11 位手机号。
5. 12 至 19 位连续长数字。

当前可识别的凭据标签包括：

```text
api key / api_key / api-key
access token / access_token / access-token
token / password / secret
密码 / 口令 / 密钥 / 令牌
```

统一替换为：

```text
[REDACTED]
```

示例：

```text
输入：蓝牙耳机，手机号 13800138000，token=secret-value
结果：蓝牙耳机，手机号 [REDACTED]，[REDACTED]
```

### 7.1 为什么敏感信息-only 输入不保存

只把敏感值替换成占位符还不够。下面这条数据没有任何推荐价值：

```text
我的手机号是 [REDACTED]
```

因此 `hasMeaningfulContent()` 在出现占位符时，会继续去掉常见敏感标签和“我的、是、请记住”等连接词，
检查是否还剩字母或数字。没有实际内容就返回 `accepted=false`。

调用方收到拒绝结果后，会提示用户只提供商品或品类，不会把原始文本写入 Trace、Workflow 状态或回复。

### 7.2 当前规则不是完整 DLP

确定性正则适合格式稳定的高风险字段，但有两个天然边界：

- 漏报：姓名、地址、昵称、带复杂分隔符的账号可能无法识别。
- 误报：12 至 19 位合法商品数字编码也可能被当成长数字遮蔽。

因此当前代码明确不使用一个宽泛正则删除所有疑似姓名和地址。生产环境应结合结构化输入字段、业务实体
识别、分类器和专业 DLP 服务。

## 8. 为什么需要 UserMemoryWriteResult

旧接口只返回 boolean：

```text
true  = 新增
false = 重复或拒绝
```

它无法告诉 Workflow 最终安全文本是什么。即使 Store 内部已经脱敏，调用方仍可能继续使用原始
`preference`。

新结果包含：

| 字段 | 含义 |
| --- | --- |
| `accepted` | 是否允许保存 |
| `added` | 是否新增，而不是重复刷新 |
| `storedPreference` | 最终保存的安全文本 |

不变量：

- 拒绝结果不能包含 storedPreference。
- 接受结果必须包含非空 storedPreference。
- Workflow、Trace、任务状态和回复只使用 storedPreference。

`UserProfileWorkflow` 已使用这个安全结果。Phase 9 前保留的 Legacy `CustomerServiceSkill` 也同步使用，
避免回滚路径成为隐私旁路。

## 9. 会话摘要上限和旧快照恢复

### 9.1 新摘要生成

`ConversationState` 的摘要上限是实例策略，不是全局可变静态变量。Spring 运行时由
`ConversationStore` 从 `MemorySafetyProperties` 读取配置，并在创建会话时传入。

摘要生成过程：

```text
较早消息
  -> 每条保留最多 42 个 Unicode 字符的规则片段
  -> 先执行高风险格式脱敏
  -> 与旧 summaryMemory 合并
  -> 再次整体脱敏
  -> 超过 summary-max-length 时从旧内容一侧截断
  -> 保留最近摘要内容
```

为什么保留尾部？

对话中最近形成的上下文通常比很早的历史更可能影响当前任务。摘要超长时从前面淘汰，比只保留最早内容
更符合客服对话的时序。

### 9.2 恢复旧 Session 快照

数据库或旧内存快照可能在本批发布前已经包含超长或未脱敏摘要。恢复时不能直接相信历史数据：

```text
SessionStore.load()
  -> WorkflowStateMigrationRegistry.restore(snapshot, summaryLimit)
  -> ConversationState.restoreMigrated()
  -> redactAndKeepTail(oldSummary, summaryLimit)
```

这样 Runtime 和 Memory API 看到的是安全摘要。

需要注意：只读 `ConversationStore.get()` 不会立即把清洗后的摘要回写数据库。下一次成功执行并保存会话
后，安全摘要才会覆盖旧快照。若要满足“发布后立即物理清洗历史数据”，需要独立离线迁移任务。

### 9.3 摘要上限不等于全链路上限

当前只限制 `summaryMemory`。最近消息窗口仍按消息条数限制，单条消息正文还没有统一长度上限；原始
Prompt、Trace、工具参数和 MCP 审计也尚未全部走同一个 Redactor。

## 10. 并发和性能取舍

`UserMemoryStore` 的写入、读取和清除入口使用 `synchronized`，因为 `LinkedHashMap` 自身不是线程安全的，
而一次操作通常包含多个步骤：

```text
清理过期项 -> 访问顺序变化 -> 写入 -> 容量淘汰 -> 生成快照
```

只换成并发 Map 不能保证这整组操作原子执行。

当前取舍适合最多 10000 用户的学习和 Demo 场景：

- 实现清楚，测试稳定。
- 所有不变量在一把锁内维护。
- 不需要引入缓存框架。

生产高并发场景不能继续依赖单实例 synchronized Map。应拆出 `UserMemoryRepository` 或类似 SPI，并把
TTL、唯一性、容量和并发控制交给 Redis、数据库或独立画像服务。

## 11. 测试覆盖

| 测试 | 主要验证内容 |
| --- | --- |
| `MemorySafetyPropertiesTest` | TTL、容量和长度不能为 0 或负数 |
| `SensitiveTextRedactorTest` | 中英文凭据、邮箱、手机号、身份证、长数字和 Unicode 边界 |
| `UserMemoryStoreTest` | TTL 到期、重复刷新、两级淘汰、敏感-only 拒绝、单条长度 |
| `ConversationStateMemoryTest` | 摘要脱敏、摘要上限、旧快照恢复和配置传递 |
| `UserProfileWorkflowTest` | 新架构拒绝不安全偏好且不传播原始文本 |
| `CustomerServiceSkillTest` | Legacy 回滚路径同样拒绝不安全偏好 |
| `SmartCsAgentApplicationTests` | Spring 配置绑定和 Bean 注入正常启动 |

完整测试结果：

```text
mvn test
Tests run: 324, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完整工程校验结果：

```text
mvn -q -DskipTests package
BUILD SUCCESS

docker compose config --quiet
通过（仅提示本地未设置可选 OpenAI 环境变量）

git diff --check
通过
```

## 12. 当前限制

1. `UserMemoryStore` 仍是进程内实现，服务重启后长期偏好会丢失。
2. 多实例不共享长期偏好，也不共享用户级访问顺序。
3. TTL 是惰性清理，不是后台定时删除。
4. 写入时扫描当前用户清理过期项，虽然有 `max-users` 上限，但不适合无限规模。
5. 姓名、地址等上下文型敏感字段尚未自动识别。
6. 正则脱敏可能漏掉非标准格式，也可能遮蔽合法的长数字商品编码。
7. 最近消息、完整 Prompt、Trace、工具参数和 MCP 审计尚未统一脱敏。
8. 旧数据库摘要只在运行时清洗，读操作不会立即物理回写。
9. 没有用户确认、单条撤销、来源证据、数据导出和持久化审计。
10. `UserMemoryStore` 目前还是具体类，不是可插拔持久化 SPI。

## 13. 下一步建议

按 Phase 8 当前顺序，下一批优先处理：

1. 为 Chat、Memory、Monitor 和 MCP 管理接口接入统一真实 Principal。
2. 给管理接口增加角色权限。
3. 建立 Trace / Prompt / Tool / MCP 审计统一的敏感字段策略和测试夹具。
4. 区分业务拒绝、技术失败、Guardrail 拒绝和用户取消指标。

长期记忆后续生产化可以独立推进：

1. 抽出 `UserMemoryRepository` SPI，不让 Workflow 依赖具体内存类。
2. 增加 JDBC / Redis 实现和跨实例一致性测试。
3. 为每条偏好保存来源、时间、置信度和用户确认状态。
4. 支持单条撤销、个人数据导出和删除审计。
5. 使用数据库 TTL 索引、Redis TTL 或后台任务做物理过期清理。

本批完成后，长期记忆不会再无限增长，也不会直接把常见高风险格式原样写入偏好和摘要；同时文档明确
保留了“当前只完成 Memory 局部安全边界，尚未完成全系统隐私治理”这一事实。
