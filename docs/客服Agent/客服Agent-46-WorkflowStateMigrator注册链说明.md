# 客服Agent-46：WorkflowStateMigrator 注册链说明

## 1. 本批次结论

Phase 8 第五批把 Workflow 私有状态从“只能读取当前 v1”升级为可注册的逐版本迁移机制：

```text
旧会话中的 WorkflowState v1
              |
              v
WorkflowStateMigrator 1 -> 2
              |
              v
WorkflowStateMigrator 2 -> 3
              |
              v
当前代码只接收 v3 payload
```

现在具备以下能力：

- 每个 Workflow 可以独立演进自己的 stateSchemaVersion。
- Spring 自动收集 `WorkflowStateMigrator` Bean，新迁移器不需要修改中心 switch。
- 注册表在应用启动时拒绝重复版本、跳版本和迁移链缺口。
- 恢复旧会话时严格按 `1 -> 2 -> 3` 顺序迁移，不允许跳步猜测。
- 数据库中的版本高于当前代码时失败关闭，防止旧程序误读新格式。
- 任一步迁移失败时整体失败，不返回半迁移状态。
- 错误消息不回显可能包含用户信息的 payload。
- 下一次成功保存会话时，SessionStore 会写入该 Workflow 的当前版本。
- `AgentRuntime`、Router、Gateway 和 JSON Codec 不包含具体业务迁移逻辑。

本批没有为了演示强行修改某个真实业务 Workflow 的状态结构。当前生产 Workflow 仍使用 v1；迁移
基础设施通过合成 `1 -> 2 -> 3` 链和 ConversationStore 端到端测试验证。等真正出现字段重命名或
结构变化时，再由对应 Workflow 添加业务迁移器。

## 2. 为什么只有 schemaVersion 还不够

第二批已经给每个 Workflow payload 保存了 `schemaVersion=1`，也会拒绝未知未来版本。这只能回答：

```text
我看不懂 v2，所以拒绝恢复。
```

但线上升级真正需要回答的是：

```text
数据库中还有 v1 会话，新代码只理解 v3，怎样安全地把 v1 变成 v3？
```

如果在 `ConversationStateCodec` 中直接写：

```java
if (workflowId.equals("return_apply") && version == 1) {
    // 修改字段
}
```

以后每新增一个 Workflow 或版本，Codec 都要继续增加业务分支。最终 JSON 基础设施会知道退货、订单、
商品等所有业务细节，又回到“能力全堆在中心类”的问题。

本批改为注册式扩展：迁移规则由状态的所有者提供，中心注册表只负责排序、校验和执行。

## 3. 组件结构

```text
具体 Workflow 包
  `-- XxxStateV1ToV2Migrator
          |
          | implements
          v
workflow/api/WorkflowStateMigrator
          |
          | Spring List 注入
          v
agent/context/WorkflowStateMigrationRegistry
          |
          +-- 启动期：校验重复、跳版本、断链
          +-- 读取时：v1 -> v2 -> ... -> current
          `-- 保存时：写入 current stateSchemaVersion
                         |
                         v
                  ConversationStore
                         |
                         v
                    SessionStore SPI
```

职责表：

| 组件 | 负责什么 | 不负责什么 |
| --- | --- | --- |
| `WorkflowStateMigrator` | 描述一个 Workflow 的一步状态转换 | 查数据库、调用模型、处理其他 Workflow |
| `WorkflowStateMigrationRegistry` | 注册、校验、排序、逐步执行和版本标记 | 退货/订单等具体字段含义 |
| `ConversationStore` | 在会话加载和保存边界调用注册表 | 编写业务迁移规则 |
| `ConversationStateCodec` | JSON 与外层 Conversation schema | 判断某个 Workflow 当前是 v2 还是 v3 |
| `AgentRuntime` / Router / Gateway | 通用编排、路由和能力执行 | 会话状态迁移 |

## 4. SPI 为什么只表示“一步”

接口位于：

```text
src/main/java/com/smartcs/smart_cs_agent/workflow/api/WorkflowStateMigrator.java
```

核心契约：

```java
public interface WorkflowStateMigrator {
    String workflowId();
    int fromVersion();
    int toVersion();
    Map<String, String> migrate(Map<String, String> payload);
}
```

一个实现只能负责相邻版本：

```text
允许：1 -> 2
允许：2 -> 3
拒绝：1 -> 3
```

不允许直接 `1 -> 3` 的原因：

1. v2 的字段变化可能也是 v3 迁移的输入前提。
2. 每一步更小，更容易单测和代码审查。
3. v1、v2、v3 会话可以复用同一组迁移器。
4. 新增 v4 时只添加 `3 -> 4`，不用修改历史代码。

迁移器必须是确定、无副作用的纯转换：

- 不调用模型。
- 不访问数据库或远程 MCP。
- 不创建工单、退款等业务副作用。
- 不修改传入的不可变 Map。
- 相同输入重复执行应得到相同输出。

这是因为只读 Memory 接口、聊天恢复和故障重试都可能再次执行同一迁移。

## 5. 注册表如何在启动时失败关闭

`WorkflowStateMigrationRegistry` 会把迁移器按：

```text
(workflowId, fromVersion)
```

建立唯一索引，然后检查整条链。

| 非法配置 | 结果 | 原因 |
| --- | --- | --- |
| 两个 `return_apply 1 -> 2` | 启动失败 | 同一步规则不明确 |
| 只有 `2 -> 3` | 启动失败 | 缺少 `1 -> 2` |
| `1 -> 3` | 启动失败 | 跳过中间 schema |
| 空 Workflow ID | 启动失败 | 无法建立命名空间 |
| ID 前后有空格 | 启动失败 | 防止注册 ID 与持久化 key 不一致 |

为什么要在启动时失败，而不是用户恢复时再失败：

```text
错误迁移配置属于发布问题，不应该等某个老用户再次打开会话才被发现。
```

## 6. 读取旧会话的完整流程

```text
SessionStore.load(key)
        |
        v
ConversationStateSnapshot
        |
        v
WorkflowStateMigrationRegistry.migrateSnapshot()
        |
        +-- workflow_a v1 -> v2 -> v3
        |
        +-- workflow_b 已是 v2，不重复执行 1 -> 2
        |
        `-- workflow_c 没有迁移器，仍按基线 v1
        |
        v
ConversationState.restoreMigrated()
        |
        v
Workflow 只读取当前 payload
```

每个 Workflow 的命名空间分别迁移。`return_apply` 的迁移器不能看到或修改 `order_query` 状态。

如果数据库版本高于当前代码：

```text
storedVersion=4
currentVersion=3
        |
        v
UnsupportedWorkflowStateSchemaException
```

系统不会尝试降级读取，因为新版本可能已经删除、拆分或改变字段语义。

## 7. 保存时为什么还要经过注册表

运行时 `ConversationState` 只保存：

```text
workflowId -> Map<String, Object>
```

它不把 schemaVersion 混入每个业务读写方法。生成持久化快照时，注册表根据已注册链计算：

```text
没有迁移器             -> currentVersion = 1
注册 1 -> 2            -> currentVersion = 2
注册 1 -> 2 和 2 -> 3  -> currentVersion = 3
```

然后把版本写入 `WorkflowStateSnapshot`。这样业务 State 访问器只关心字段，版本知识集中在一个位置。

旧状态在只读 `get()` 时只在内存中迁移，不会偷偷写数据库；下一次成功聊天并通过 CAS 保存后，才会
把当前版本写回。这避免只读接口产生隐藏写操作，但也意味着纯只读会话可能重复执行迁移。

## 8. 为什么 Codec 不再校验 Workflow 当前版本

`ConversationStateCodec` 仍然校验：

- JSON 是否可解析。
- JSON 内的 Conversation schemaVersion 是否与数据库列一致。
- 外层 Conversation schema 是否是当前版本。

它不再使用一个全局常量要求所有 Workflow 都必须是 v1。原因是不同 Workflow 可以同时处于：

```text
return_apply  -> state schema v3
order_query   -> state schema v1
policy_qa     -> state schema v2
```

Codec 只负责格式；每个 Workflow 的当前版本和迁移链由注册表判断。这不是放松校验，而是把校验移动
到拥有正确上下文的位置。

## 9. 将来怎样添加一个真实迁移器

假设退货状态 v2 把 `reason` 重命名为 `returnReason`，可以在退货包中新增：

```java
@Component
public class ReturnApplyStateV1ToV2Migrator implements WorkflowStateMigrator {

    @Override
    public String workflowId() {
        return "return_apply";
    }

    @Override
    public int fromVersion() {
        return 1;
    }

    @Override
    public int toVersion() {
        return 2;
    }

    @Override
    public Map<String, String> migrate(Map<String, String> payload) {
        Map<String, String> migrated = new LinkedHashMap<>(payload);
        String reason = migrated.remove("reason");
        if (reason != null) {
            migrated.put("returnReason", reason);
        }
        return migrated;
    }
}
```

同时修改当前 `ReturnApplyState` 使用 `returnReason`。不需要修改：

- `ConversationStore`
- `ConversationStateCodec`
- `AgentRuntime`
- `WorkflowRouter`
- `DefaultCapabilityGateway`

新增 v3 时再增加一个独立的 `2 -> 3` Bean。

## 10. 测试覆盖

| 测试 | 验证内容 |
| --- | --- |
| `WorkflowStateMigrationRegistryTest` | `1 -> 2 -> 3` 顺序、原快照不变、命名空间隔离 |
| 同上 | ConversationStore 加载迁移、成功一轮后写入当前版本 |
| 同上 | 重复 sourceVersion 启动失败 |
| 同上 | 断链、跳版本、非规范 ID 启动失败 |
| 同上 | 已知/未知 Workflow 的未来版本失败关闭 |
| 同上 | null/非法迁移结果失败且错误不泄露 payload |
| `ConversationStatePersistenceTest` | Codec 保留版本元数据，Registry 决定是否支持 |
| `NewArchitectureBoundaryTest` | Runtime、Router、Gateway、Codec 不编排迁移 |
| `SmartCsAgentApplicationTests` | 没有迁移器 Bean 时 Spring 注入空列表并正常启动 |

完整验证结果：

```text
mvn test
Tests run: 313, Failures: 0, Errors: 0, Skipped: 0

mvn -q -DskipTests package
BUILD SUCCESS
```

## 11. 发布和回滚必须注意什么

注册迁移器后，新实例会在下一次成功保存时写入新版本。滚动发布期间可能出现：

```text
新实例写出 v2
        |
        v
旧实例仍只理解 v1
        |
        v
旧实例恢复同一会话失败关闭
```

因此第一次真实升级不能只写一个迁移类就直接灰度。应选择以下策略之一：

1. 先发布能同时读取 v1/v2、但仍写 v1 的兼容版本，再启用 v2 写入。
2. 发布时先摘除旧实例，确认不再接流量后再允许新版本写入。
3. 使用配置化 writeVersion，把“代码具备读取能力”和“开始写新格式”拆成两个步骤。
4. 回滚前确认数据库中是否已有新版本；旧程序不能靠猜测降级读取。

当前项目还没有 writeVersion 开关，因为尚未注册真实业务 v2。这个能力应在第一次生产 schema 升级前
补充，而不是等回滚时再处理。

## 12. 当前限制

1. 本批只实现 WorkflowState 迁移；外层 Conversation schema 和 CapabilityResult 仍只有版本拒绝。
2. 当前没有真实业务 Workflow 升级到 v2，避免为了演示制造无意义字段变化。
3. 迁移只处理 `Map<String, String>`；结构化对象仍需先设计新的稳定 DTO/JSON schema。
4. 只读恢复不会立即回写，长期不产生新一轮的旧会话会保持旧版本。
5. 尚未记录“迁移次数、来源版本、失败次数”等 Monitor 指标。
6. 尚未提供 writeVersion 开关和自动回滚兼容策略。
7. 迁移器运行在请求线程，必须保持轻量、纯函数和无外部 I/O。

## 13. 下一批建议

后续可以继续：

1. 在第一次真实 Workflow schema 变化时增加业务迁移器和 writeVersion 灰度开关。
2. 为外层 ConversationStateSnapshot 和 CapabilityResultSnapshot 分别设计迁移注册表。
3. 增加迁移成功/失败 Monitor 指标，但不记录 payload。
4. 使用 Testcontainers MySQL 验证旧 JSON 从数据库读取、迁移和 CAS 回写。
5. 长期记忆 TTL、容量和脱敏已在 Phase 8 第六批完成；下一步继续真实 Principal、管理接口权限和
   Trace / Prompt / MCP 审计统一脱敏。

本批完成后，状态格式演进已经从“修改中心 Codec”变成“由 Workflow 自己注册下一步迁移”，扩展点和
失败边界都已经明确。
