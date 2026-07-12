package com.smartcs.smart_cs_agent.agent.context;

import com.smartcs.smart_cs_agent.workflow.api.WorkflowStateMigrator;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * WorkflowStateMigrator 的不可变注册表和逐版本执行链。
 *
 * <p>Spring 会自动收集所有 WorkflowStateMigrator Bean。注册表在应用启动时检查重复版本、跳版本和
 * 缺口，避免等到用户恢复旧会话时才发现迁移链不完整。</p>
 *
 * <p>这里是通用会话基础设施，不包含任何 return_apply、order_query 等业务判断。以后某个 Workflow
 * 升级状态格式时，只需在自己的包中新增下一步迁移器。</p>
 */
@Component
public class WorkflowStateMigrationRegistry {

    private final Map<String, MigrationChain> chainsByWorkflowId;

    public WorkflowStateMigrationRegistry(List<WorkflowStateMigrator> migrators) {
        this.chainsByWorkflowId = buildChains(migrators);
    }

    /**
     * 恢复会话前先把每个 Workflow payload 迁移到当前版本。
     *
     * <p>迁移完成后才交给 ConversationState 创建运行时对象，业务代码永远只看到当前格式。</p>
     */
    public ConversationState restore(ConversationStateSnapshot snapshot) {
        return ConversationState.restoreMigrated(migrateSnapshot(snapshot));
    }

    /**
     * 把数据库/内存 Store 中的快照逐步迁移到当前版本。
     *
     * <p>该方法不修改传入对象。任一步失败都会整体失败关闭，不会返回半迁移状态。</p>
     */
    public ConversationStateSnapshot migrateSnapshot(ConversationStateSnapshot snapshot) {
        requireCurrentConversationSchema(snapshot);

        Map<String, WorkflowStateSnapshot> migratedStates = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, WorkflowStateSnapshot> entry : snapshot.workflowStates().entrySet()) {
            WorkflowStateSnapshot migrated = migrateWorkflowState(entry.getKey(), entry.getValue());
            migratedStates.put(entry.getKey(), migrated);
            changed = changed || migrated != entry.getValue();
        }
        return changed ? copyWithWorkflowStates(snapshot, migratedStates) : snapshot;
    }

    /**
     * 生成写入 SessionStore 的快照，并把每个 Workflow 标记为注册链的当前版本。
     *
     * <p>ConversationState 的运行时 Map 不携带持久化版本；版本知识集中在本注册表，避免所有
     * State 访问器都重复保存 schemaVersion。</p>
     */
    public ConversationStateSnapshot snapshotForPersistence(ConversationState state) {
        Objects.requireNonNull(state, "conversation state cannot be null");
        ConversationStateSnapshot runtimeSnapshot = state.toPersistenceSnapshot();
        Map<String, WorkflowStateSnapshot> versionedStates = new LinkedHashMap<>();

        for (Map.Entry<String, WorkflowStateSnapshot> entry : runtimeSnapshot.workflowStates().entrySet()) {
            WorkflowStateSnapshot workflowState = entry.getValue();
            if (workflowState.schemaVersion() != WorkflowStateSnapshot.BASE_SCHEMA_VERSION) {
                throw new WorkflowStateMigrationException(
                        "Runtime workflow snapshot must use base schemaVersion for " + entry.getKey()
                );
            }
            versionedStates.put(
                    entry.getKey(),
                    new WorkflowStateSnapshot(
                            currentVersion(entry.getKey()),
                            workflowState.payload()
                    )
            );
        }
        return copyWithWorkflowStates(runtimeSnapshot, versionedStates);
    }

    /** 没有注册迁移器的 Workflow 仍使用 v1。 */
    public int currentVersion(String workflowId) {
        String normalizedWorkflowId = requireWorkflowId(workflowId);
        MigrationChain chain = chainsByWorkflowId.get(normalizedWorkflowId);
        return chain == null ? WorkflowStateSnapshot.BASE_SCHEMA_VERSION : chain.currentVersion();
    }

    private WorkflowStateSnapshot migrateWorkflowState(
            String workflowId,
            WorkflowStateSnapshot storedState) {
        int currentVersion = currentVersion(workflowId);
        if (storedState.schemaVersion() > currentVersion) {
            throw new UnsupportedWorkflowStateSchemaException(
                    workflowId,
                    storedState.schemaVersion(),
                    currentVersion
            );
        }
        if (storedState.schemaVersion() == currentVersion) {
            return storedState;
        }

        MigrationChain chain = chainsByWorkflowId.get(workflowId);
        WorkflowStateSnapshot current = storedState;
        while (current.schemaVersion() < currentVersion) {
            WorkflowStateMigrator migrator = chain.byFromVersion().get(current.schemaVersion());
            if (migrator == null) {
                // 构造注册表时已经检查过连续性；保留运行时保护，防止以后重构破坏不变量。
                throw new WorkflowStateMigrationException(
                        "Missing workflow state migrator for " + workflowId
                                + " from version " + current.schemaVersion()
                );
            }
            current = applyMigrator(workflowId, current, migrator);
        }
        return current;
    }

    private WorkflowStateSnapshot applyMigrator(
            String workflowId,
            WorkflowStateSnapshot source,
            WorkflowStateMigrator migrator) {
        try {
            Map<String, String> migratedPayload = migrator.migrate(source.payload());
            if (migratedPayload == null) {
                throw new IllegalArgumentException("migrator returned null payload");
            }
            return new WorkflowStateSnapshot(migrator.toVersion(), migratedPayload);
        } catch (RuntimeException migrationFailure) {
            // 错误消息只包含 Workflow 和版本，不回显可能含用户内容的 payload。
            throw new WorkflowStateMigrationException(
                    "Failed to migrate workflow state for " + workflowId
                            + " from version " + migrator.fromVersion()
                            + " to " + migrator.toVersion(),
                    migrationFailure
            );
        }
    }

    private Map<String, MigrationChain> buildChains(Collection<WorkflowStateMigrator> migrators) {
        Map<String, NavigableMap<Integer, WorkflowStateMigrator>> indexed = new LinkedHashMap<>();
        if (migrators != null) {
            for (WorkflowStateMigrator migrator : migrators) {
                register(indexed, Objects.requireNonNull(migrator, "workflow state migrator cannot be null"));
            }
        }

        Map<String, MigrationChain> chains = new LinkedHashMap<>();
        for (Map.Entry<String, NavigableMap<Integer, WorkflowStateMigrator>> entry : indexed.entrySet()) {
            int expectedFromVersion = WorkflowStateSnapshot.BASE_SCHEMA_VERSION;
            Map<Integer, WorkflowStateMigrator> ordered = new LinkedHashMap<>();
            for (WorkflowStateMigrator migrator : entry.getValue().values()) {
                if (migrator.fromVersion() != expectedFromVersion) {
                    throw new IllegalArgumentException(
                            "Missing workflow state migrator for " + entry.getKey()
                                    + " from version " + expectedFromVersion
                    );
                }
                ordered.put(migrator.fromVersion(), migrator);
                expectedFromVersion = migrator.toVersion();
            }
            chains.put(
                    entry.getKey(),
                    new MigrationChain(
                            expectedFromVersion,
                            Collections.unmodifiableMap(ordered)
                    )
            );
        }
        return Collections.unmodifiableMap(chains);
    }

    private void register(
            Map<String, NavigableMap<Integer, WorkflowStateMigrator>> indexed,
            WorkflowStateMigrator migrator) {
        String workflowId = requireWorkflowId(migrator.workflowId());
        if (migrator.fromVersion() < WorkflowStateSnapshot.BASE_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Workflow state fromVersion must be positive");
        }
        if (migrator.toVersion() != migrator.fromVersion() + 1) {
            throw new IllegalArgumentException(
                    "Workflow state migrator must advance exactly one version for " + workflowId
            );
        }

        NavigableMap<Integer, WorkflowStateMigrator> byVersion = indexed.computeIfAbsent(
                workflowId,
                ignored -> new TreeMap<>()
        );
        WorkflowStateMigrator duplicate = byVersion.putIfAbsent(migrator.fromVersion(), migrator);
        if (duplicate != null) {
            throw new IllegalArgumentException(
                    "Duplicate workflow state migrator for " + workflowId
                            + " from version " + migrator.fromVersion()
            );
        }
    }

    private void requireCurrentConversationSchema(ConversationStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (snapshot.schemaVersion() != ConversationStateSnapshot.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported conversation state schemaVersion: " + snapshot.schemaVersion()
            );
        }
    }

    private String requireWorkflowId(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId cannot be empty");
        }
        String normalized = workflowId.trim();
        if (!workflowId.equals(normalized)) {
            throw new IllegalArgumentException("workflowId cannot contain surrounding whitespace");
        }
        return normalized;
    }

    private ConversationStateSnapshot copyWithWorkflowStates(
            ConversationStateSnapshot source,
            Map<String, WorkflowStateSnapshot> workflowStates) {
        return new ConversationStateSnapshot(
                source.schemaVersion(),
                source.userId(),
                source.message(),
                source.messages(),
                source.activeIntent(),
                source.taskState(),
                source.summaryMemory(),
                source.totalMessageCount(),
                source.summaryVersion(),
                source.summaryUpdatedAt(),
                source.activeWorkflowId(),
                source.workflowVersion(),
                source.workflowStatus(),
                source.workflowStartedAt(),
                source.workflowUpdatedAt(),
                workflowStates
        );
    }

    private record MigrationChain(
            int currentVersion,
            Map<Integer, WorkflowStateMigrator> byFromVersion) {
    }
}
