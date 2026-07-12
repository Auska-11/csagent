# 客服Agent-43：SessionStore 持久化与恢复说明

## 1. 本批次结论

Phase 8 第二批已经把会话存储从 `ConversationStore` 内部的 Map 拆成可替换 SPI：

```text
ConversationStore
        |
        v
SessionStore
   |- InMemorySessionStore
   `- JdbcSessionStore
```

现在具备以下能力：

- 默认 memory 模式继续适合单元测试和本地 Demo。
- `smartcs.persistence.mode=jdbc` 时，会话写入 MySQL 的 `cs_agent_session` 表。
- 服务重建 `ConversationStore` 后，可以从 SessionStore 恢复等待用户的 Workflow。
- 每次会话保存都携带 expectedVersion，旧版本不能覆盖新版本。
- 会话 JSON 和每个 Workflow payload 都有独立 schemaVersion。
- Observation、ToolResult 和本轮 Runtime 临时状态不会写入数据库。
- 版本冲突返回 HTTP 409，而不是静默覆盖。

这仍然是学习型持久化实现。CAS 解决的是“状态丢失更新”，不能单独保证多实例下副作用只执行一次。
真实 MySQL 集成测试、连接池、数据库迁移工具和持久化幂等仍需继续实现。

## 2. 为什么要拆 SessionStore

旧 `ConversationStore` 同时负责：

1. 保存 `Map<ConversationKey, ConversationState>`。
2. 创建和管理会话锁。
3. 判断新会话还是旧会话。
4. 执行 Workflow 后保存状态。

这在内存 Demo 中可以工作，但只要换 MySQL、Redis 或远程 Session 服务，就必须修改 Agent 的核心
会话类。存储细节会继续向上渗透，最后又变成所有能力堆在一个类里。

拆分后的职责是：

| 组件 | 只负责什么 | 不负责什么 |
| --- | --- | --- |
| `ConversationStore` | 本轮加载、owner 校验、本地锁、调用 operation、CAS 保存 | SQL、JSON、Map 存储实现 |
| `SessionStore` | 版本化加载、保存和删除契约 | Workflow 路由和 Agent 执行 |
| `InMemorySessionStore` | 单进程 Map 与原子 revision CAS | 跨进程恢复 |
| `JdbcSessionStore` | MySQL 表、JSON、SQL CAS | 业务状态解释 |
| `ConversationStateCodec` | Snapshot 与 JSON 的转换、schema 检查 | 运行 Workflow |

以后增加 Redis 时，只需要新增 `RedisSessionStore implements SessionStore`，`CustomerServiceAgent`、
`AgentRuntime`、Router 和 Workflow 都不需要认识 Redis。

## 3. 为什么不能直接序列化 ConversationState

`ConversationState` 是运行时领域对象，不是数据库 DTO。直接执行：

```java
objectMapper.writeValueAsString(conversationState);
```

会产生多种问题。

### 3.1 普通构造函数会重复追加消息

正常创建 `ConversationState(message, userId)` 时会自动把第一条用户消息放入历史并增加计数。
如果恢复时也走该构造函数，再把数据库消息复制回来，就会重复一条消息，`totalMessageCount` 也会错误。

本批次增加专用 restore 路径，恢复时不会执行“新会话初始化”逻辑。

### 3.2 Observation 包含运行时多态对象

Observation 可能保存：

- `ToolResult`。
- `CapabilityObservation`。
- `ActionFailureObservation`。
- `OrderSummary`、`ProductSearchResult` 等业务 DTO。

如果字段类型只是 `Object`，Jackson 恢复后通常会变成 `LinkedHashMap`。现有 Workflow 会使用
`instanceof OrderSummary` 等判断，类型一旦漂移就无法继续执行。

Observation 本来就是当前一轮的临时结果，下一轮开始时也会清空，因此它完全不进入持久化快照。

### 3.3 taskState 同时包含业务槽位和运行时 scratchpad

Legacy 回滚路径仍需要跨轮保存：

```text
pending_slot
order_id
return_id
return_reason
last_user_preference
used_user_preference
```

但以下字段只服务当前一轮，不能跨重启恢复：

```text
last_capability
last_capability_result
last_llm_purpose
response_generated
intent_handled
*_result
```

因此 Snapshot 使用明确白名单，而不是把整个 `Map<String, Object>` 写入 JSON。

## 4. 持久化快照结构

新增 `ConversationStateSnapshot`，主要包含：

```text
schemaVersion
userId
message
messages
activeIntent
taskState（白名单字符串）
summaryMemory / totalMessageCount / summaryVersion / summaryUpdatedAt
activeWorkflowId
workflowVersion
workflowStatus
workflowStartedAt / workflowUpdatedAt
workflowStates
```

每个 Workflow 命名空间不再直接保存 `Map<String, Object>`，而是包装为：

```text
WorkflowStateSnapshot
  |- schemaVersion
  `- payload: Map<String, String>
```

当前生产 Workflow 写入的都是 step、订单号、原因、商品名、关键词和偏好等标量。为了避免未来有人
把任意 DTO 塞入状态，`setWorkflowState` 现在只允许：

- String。
- Number。
- Boolean。
- Enum。
- null（表示删除）。

如果以后确实需要结构化 payload，应先升级 `WorkflowStateSnapshot` schema，而不是打开 Jackson
Default Typing 或把 Java 类名写进数据库。

## 5. 三种版本不能混用

| 版本 | 示例 | 解决的问题 |
| --- | --- | --- |
| Session revision | `version=7` | CAS 并发写，防止旧状态覆盖新状态 |
| Conversation schemaVersion | `1` | 整个会话 JSON 字段结构如何解释 |
| Workflow stateSchemaVersion | `1` | 某个 Workflow payload 如何解释 |
| workflowVersion | `2.0.0` | 当前部署使用哪个 Workflow 实现版本 |

`workflowVersion=2.0.0` 不等于 `stateSchemaVersion=2`。实现代码可能发布 2.1.0，但状态格式仍是 v1；
也可能实现版本不变，却需要给状态文档增加字段。

本批次建立 v1 基线，并对未知未来版本失败关闭。注册式 Migrator 尚未实现，所以开发计划中
“schemaVersion 和迁移策略”仍保留未完成状态。

## 6. SessionStore SPI 契约

核心接口只有三个动作：

```java
Optional<SessionRecord> load(ConversationKey key);

SessionRecord save(
        ConversationKey key,
        long expectedVersion,
        ConversationStateSnapshot snapshot
);

boolean delete(ConversationKey key, long expectedVersion);
```

版本规则：

- `expectedVersion=0`：记录必须不存在，只能创建 version 1。
- `expectedVersion=N`：记录当前必须仍为 N，成功后保存成 N+1。
- 实际版本不同：抛出 `SessionVersionConflictException`。
- 删除也携带 expectedVersion，避免一个旧请求删除刚更新的会话。

`SessionRecord` 构造时还会验证 `key.userId == snapshot.userId`，防止错误 Adapter 把别人的状态装进
当前 owner 的复合键。

## 7. ConversationStore 新生命周期

一轮聊天现在按以下顺序运行：

```text
1. ConversationKey.from(ChatRequest)
2. 获取该 key 的本地 ReentrantLock
3. SessionStore.load(key)
4. Snapshot -> ConversationState.restore()
5. startNewTurn(currentMessage)
6. 执行 Router / Workflow / Capability
7. ConversationState -> Snapshot
8. SessionStore.save(key, loadedVersion, snapshot)
9. 释放本地锁
```

这和旧 Map 引用还有一个重要区别：

- 旧实现加载的是 Map 中同一个可变对象。
- operation 执行一半抛异常时，已经修改的字段仍可能留在 Map 中。
- 新实现每次恢复一份独立对象，只有 operation 成功且 CAS 保存成功才会进入 SessionStore。

测试已经验证：模拟失败 operation 写入的订单号和第二条消息都不会成为持久状态。

## 8. 内存实现

`InMemorySessionStore` 使用：

```text
ConcurrentHashMap<ConversationKey, SessionRecord>
```

保存通过 `ConcurrentHashMap.compute` 原子比较版本。它适合：

- 单元测试。
- CI。
- 不想安装 MySQL 的本地学习环境。
- 演示 SessionStore SPI 行为。

它不适合服务重启和多实例部署，因为 Map 仍属于单个 JVM。

## 9. JDBC 实现

当配置：

```yaml
smartcs:
  persistence:
    mode: jdbc
```

Spring 会选择 `JdbcSessionStore`，并创建：

```sql
CREATE TABLE IF NOT EXISTS cs_agent_session (
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    state_json LONGTEXT NOT NULL,
    schema_version INT NOT NULL,
    version BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, user_id, conversation_id),
    INDEX idx_cs_agent_session_updated_at (updated_at)
);
```

更新使用真正的版本条件：

```sql
UPDATE cs_agent_session
SET state_json = ?, schema_version = ?, version = ?, updated_at = ?
WHERE tenant_id = ?
  AND user_id = ?
  AND conversation_id = ?
  AND version = ?;
```

如果受影响行数不是 1，Adapter 会再次读取实际版本并抛出冲突，不会执行无条件 upsert 覆盖新状态。

当前 `smartcs.persistence.mode` 同时控制业务 Repository 和 SessionStore：

- memory：业务模拟数据 + InMemorySessionStore。
- jdbc：业务 MySQL 表 + JdbcSessionStore。

后续如果需要独立扩缩容，可以再拆为 `smartcs.session.store-mode`。

## 10. 重启恢复验证

新增真实 Agent 集成测试，依次执行：

```text
第一个 ConversationStore：用户说“我要退货” -> 等待订单号
重建 ConversationStore：用户说“ORD-003” -> 恢复 return_apply，等待原因
再次重建 ConversationStore：用户说“质量问题” -> 恢复并进入确认提交
```

测试经过真实：

```text
CustomerServiceAgent
  -> WorkflowRouter
  -> AgentRuntime
  -> CapabilityActionHandler
  -> order.lookup
  -> ReturnApplyWorkflow
```

恢复后继续保持原来的 `workflowStartedAt`。这很重要，因为副作用 Capability 的幂等键会使用该时间
标识一次 Workflow 实例；如果重启时重新生成，后续确认可能被误认为全新的副作用请求。

## 11. 并发与错误响应

两个不同 `ConversationStore` 实例会拥有不同的本地锁，因此可能同时读取 version 1。测试让两个
实例同时执行并保存，最终结果是：

```text
一个保存成功：version 1 -> 2
一个保存失败：expected=1, actual=2
最终状态保持 version 2，没有被旧状态覆盖
```

HTTP 层将该异常转换为：

```text
HTTP 409
errorCode = SESSION_VERSION_CONFLICT
message = Conversation changed concurrently, please retry
```

## 12. 自动化测试

本批次新增或加强：

| 场景 | 验证内容 |
| --- | --- |
| Snapshot JSON round-trip | 消息、摘要、槽位、Workflow 状态和时间戳保持一致 |
| Runtime 字段过滤 | Observation、last_capability_result 不进入 JSON |
| 外层未来 schema | 未知 Conversation schemaVersion 拒绝 |
| Workflow 未来 schema | 未知 stateSchemaVersion 拒绝 |
| 非标量 Workflow 值 | 在 setWorkflowState 现场拒绝 |
| owner 不一致 | SessionRecord/Store 拒绝保存 |
| 内存 revision | create、update、stale write conflict |
| 两实例并发 | 一个成功，一个 CAS 冲突 |
| operation 异常 | 半成品状态不落库 |
| ConversationStore 重建 | 等待中的状态可继续读取和更新 |
| 真实 Workflow 恢复 | return_apply 跨三次 Store 重建继续到确认 |
| JDBC Adapter 重建 | JSON 可重新加载，UPDATE/DELETE 包含 version 条件 |
| HTTP 冲突 | SessionVersionConflict 返回 409 |

最终验证：

```text
mvn test
Tests run: 284, Failures: 0, Errors: 0, Skipped: 0

mvn -q -DskipTests package
BUILD SUCCESS
```

## 13. 当前限制

1. CI 没有真实 MySQL 或 Testcontainers；JdbcSessionStore 当前通过 SQL/RowMapper 测试替身验证。
2. `JdbcDatabaseClient` 每次操作使用 DriverManager 新连接，没有 HikariCP 和事务 API。
3. 表结构仍由代码执行 `CREATE TABLE IF NOT EXISTS`，没有 Flyway/Liquibase 迁移历史。
4. schemaVersion 已建立，但注册式 Conversation/Workflow Migrator 尚未实现。
5. CAS 本身只防止丢失更新；Capability 的跨实例重复执行已由下一批 JDBC 幂等 Store 缓解，
   详见 `客服Agent-44-Capability幂等持久化与租约说明.md`。
6. 部分领域锁仍然是进程内实现，领域表唯一约束和事务一致性尚未补齐。
7. 物理删除后 revision 会从 1 重新开始，极端情况下仍有 ABA 风险；生产方案应增加 generation 或软删除。
8. state_json 包含用户消息和业务槽位，尚未实现字段加密、TTL、容量限制和脱敏。
9. 本地 ReentrantLock 表尚未做安全淘汰，长时间创建大量会话会积累轻量锁对象。
10. readiness 尚未检查数据库连接和会话表可用性。

## 14. 下一批建议

后续继续解决持久化幂等完成后仍存在的生产硬化问题：

1. 为退货申请增加数据库唯一业务约束。
2. 实现 WorkflowStateMigrator 注册表，不在 Codec/Router 中写业务 switch。
3. 增加 Testcontainers MySQL 契约测试和数据库 readiness。
4. 评估 Session lease、事务锁、outbox 或队列串行化。
5. 为幂等记录增加 TTL 清理和真实数据库故障注入测试。

Capability 幂等 SPI、数据库唯一键、租约和结果回放已经在下一批完成；剩余工作重点是关闭
Provider 副作用与幂等结果落库之间的崩溃窗口。
