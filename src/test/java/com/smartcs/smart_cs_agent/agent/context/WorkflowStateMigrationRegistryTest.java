package com.smartcs.smart_cs_agent.agent.context;

import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.memory.InMemorySessionStore;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowStateMigrator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workflow 状态迁移链的注册校验、逐级执行和 ConversationStore 接入测试。 */
class WorkflowStateMigrationRegistryTest {

    private static final String WORKFLOW_ID = "test_workflow";

    @Test
    void shouldApplyOrderedChainWithoutChangingOtherWorkflowNamespaces() {
        WorkflowStateMigrationRegistry registry = new WorkflowStateMigrationRegistry(List.of(
                // 故意把 2 -> 3 放在前面，证明注册表按版本排序，不依赖 Spring Bean 注入顺序。
                migrator(WORKFLOW_ID, 2, 3, payload -> {
                    Map<String, String> migrated = new LinkedHashMap<>(payload);
                    migrated.put("confirmationMode", "explicit");
                    return migrated;
                }),
                migrator(WORKFLOW_ID, 1, 2, payload -> {
                    Map<String, String> migrated = new LinkedHashMap<>(payload);
                    String reason = migrated.remove("reason");
                    if (reason != null) {
                        migrated.put("returnReason", reason);
                    }
                    migrated.put("v1Migration", "applied");
                    return migrated;
                })
        ));
        ConversationStateSnapshot legacy = snapshot(Map.of(
                WORKFLOW_ID,
                new WorkflowStateSnapshot(1, Map.of("reason", "damaged")),
                "other_workflow",
                new WorkflowStateSnapshot(1, Map.of("step", "WAITING_USER"))
        ));

        ConversationStateSnapshot migrated = registry.migrateSnapshot(legacy);

        WorkflowStateSnapshot migratedWorkflow = migrated.workflowStates().get(WORKFLOW_ID);
        assertEquals(3, migratedWorkflow.schemaVersion());
        assertEquals("damaged", migratedWorkflow.payload().get("returnReason"));
        assertEquals("explicit", migratedWorkflow.payload().get("confirmationMode"));
        assertEquals("applied", migratedWorkflow.payload().get("v1Migration"));
        assertFalse(migratedWorkflow.payload().containsKey("reason"));
        assertEquals(1, migrated.workflowStates().get("other_workflow").schemaVersion());

        // 迁移输入是不可变快照，原始 v1 数据不能被迁移器原地修改。
        assertEquals(1, legacy.workflowStates().get(WORKFLOW_ID).schemaVersion());
        assertEquals("damaged", legacy.workflowStates().get(WORKFLOW_ID).payload().get("reason"));

        ConversationState restored = registry.restore(legacy);
        assertEquals("damaged", restored.getWorkflowState(WORKFLOW_ID, "returnReason"));
        ConversationStateSnapshot persisted = registry.snapshotForPersistence(restored);
        assertEquals(3, persisted.workflowStates().get(WORKFLOW_ID).schemaVersion());
        assertEquals(1, persisted.workflowStates().get("other_workflow").schemaVersion());

        ConversationStateSnapshot alreadyV2 = snapshot(Map.of(
                WORKFLOW_ID,
                new WorkflowStateSnapshot(2, Map.of("returnReason", "scratched"))
        ));
        WorkflowStateSnapshot migratedFromV2 = registry.migrateSnapshot(alreadyV2)
                .workflowStates()
                .get(WORKFLOW_ID);
        assertEquals(3, migratedFromV2.schemaVersion());
        assertEquals("explicit", migratedFromV2.payload().get("confirmationMode"));
        assertFalse(migratedFromV2.payload().containsKey("v1Migration"));
    }

    @Test
    void shouldMigrateDuringConversationLoadAndPersistCurrentVersionAfterTheTurn() {
        WorkflowStateMigrationRegistry registry = new WorkflowStateMigrationRegistry(List.of(
                migrator(WORKFLOW_ID, 1, 2, payload -> {
                    Map<String, String> migrated = new LinkedHashMap<>(payload);
                    migrated.put("format", "v2");
                    return migrated;
                })
        ));
        InMemorySessionStore backend = new InMemorySessionStore();
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "migration-conv");
        backend.save(
                key,
                0,
                snapshot(Map.of(
                        WORKFLOW_ID,
                        new WorkflowStateSnapshot(1, Map.of("step", "WAITING_USER"))
                ))
        );
        ConversationStore store = new ConversationStore(backend, registry);

        ConversationState readOnly = store.get(key);
        assertEquals("v2", readOnly.getWorkflowState(WORKFLOW_ID, "format"));
        SessionRecord afterRead = backend.load(key).orElseThrow();
        assertEquals(1, afterRead.version());
        assertEquals(1, afterRead.snapshot().workflowStates().get(WORKFLOW_ID).schemaVersion());

        store.executeSerially(
                new ChatRequest("migration-conv", "USER-001", "continue"),
                state -> {
                    assertEquals("v2", state.getWorkflowState(WORKFLOW_ID, "format"));
                    state.setWorkflowState(WORKFLOW_ID, "answer", "confirmed");
                    return null;
                }
        );

        SessionRecord saved = backend.load(key).orElseThrow();
        assertEquals(2, saved.version());
        assertEquals(2, saved.snapshot().workflowStates().get(WORKFLOW_ID).schemaVersion());
        assertEquals(
                "confirmed",
                saved.snapshot().workflowStates().get(WORKFLOW_ID).payload().get("answer")
        );
    }

    @Test
    void shouldRejectDuplicateMigratorsForTheSameSourceVersion() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowStateMigrationRegistry(List.of(
                        migrator(WORKFLOW_ID, 1, 2, UnaryOperator.identity()),
                        migrator(WORKFLOW_ID, 1, 2, UnaryOperator.identity())
                ))
        );

        assertTrue(error.getMessage().contains("Duplicate"));
    }

    @Test
    void shouldRejectGapsAndVersionJumpsWhenBuildingTheRegistry() {
        IllegalArgumentException gap = assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowStateMigrationRegistry(List.of(
                        migrator(WORKFLOW_ID, 2, 3, UnaryOperator.identity())
                ))
        );
        assertTrue(gap.getMessage().contains("from version 1"));

        IllegalArgumentException jump = assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowStateMigrationRegistry(List.of(
                        migrator(WORKFLOW_ID, 1, 3, UnaryOperator.identity())
                ))
        );
        assertTrue(jump.getMessage().contains("exactly one version"));

        IllegalArgumentException nonCanonicalId = assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowStateMigrationRegistry(List.of(
                        migrator(" test_workflow ", 1, 2, UnaryOperator.identity())
                ))
        );
        assertTrue(nonCanonicalId.getMessage().contains("surrounding whitespace"));
    }

    @Test
    void shouldRejectFutureVersionForKnownAndUnknownWorkflows() {
        WorkflowStateMigrationRegistry registry = new WorkflowStateMigrationRegistry(List.of(
                migrator(WORKFLOW_ID, 1, 2, UnaryOperator.identity())
        ));

        UnsupportedWorkflowStateSchemaException knownFuture = assertThrows(
                UnsupportedWorkflowStateSchemaException.class,
                () -> registry.migrateSnapshot(snapshot(Map.of(
                        WORKFLOW_ID,
                        new WorkflowStateSnapshot(3, Map.of("step", "future"))
                )))
        );
        assertTrue(knownFuture.getMessage().contains("current 2"));

        UnsupportedWorkflowStateSchemaException unknownFuture = assertThrows(
                UnsupportedWorkflowStateSchemaException.class,
                () -> registry.migrateSnapshot(snapshot(Map.of(
                        "unregistered_workflow",
                        new WorkflowStateSnapshot(2, Map.of("step", "future"))
                )))
        );
        assertTrue(unknownFuture.getMessage().contains("current 1"));
    }

    @Test
    void shouldFailClosedWithoutLeakingPayloadWhenMigratorReturnsInvalidData() {
        WorkflowStateMigrationRegistry registry = new WorkflowStateMigrationRegistry(List.of(
                migrator(WORKFLOW_ID, 1, 2, ignored -> null)
        ));

        WorkflowStateMigrationException error = assertThrows(
                WorkflowStateMigrationException.class,
                () -> registry.migrateSnapshot(snapshot(Map.of(
                        WORKFLOW_ID,
                        new WorkflowStateSnapshot(1, Map.of("secret", "private-value"))
                )))
        );

        assertTrue(error.getMessage().contains("from version 1 to 2"));
        assertFalse(error.getMessage().contains("private-value"));
    }

    private WorkflowStateMigrator migrator(
            String workflowId,
            int fromVersion,
            int toVersion,
            UnaryOperator<Map<String, String>> migration) {
        return new TestWorkflowStateMigrator(
                workflowId,
                fromVersion,
                toVersion,
                migration
        );
    }

    private ConversationStateSnapshot snapshot(Map<String, WorkflowStateSnapshot> workflowStates) {
        ConversationStateSnapshot base = new ConversationState(
                "legacy message",
                "USER-001"
        ).toPersistenceSnapshot();
        return new ConversationStateSnapshot(
                base.schemaVersion(),
                base.userId(),
                base.message(),
                base.messages(),
                base.activeIntent(),
                base.taskState(),
                base.summaryMemory(),
                base.totalMessageCount(),
                base.summaryVersion(),
                base.summaryUpdatedAt(),
                base.activeWorkflowId(),
                base.workflowVersion(),
                base.workflowStatus(),
                base.workflowStartedAt(),
                base.workflowUpdatedAt(),
                workflowStates
        );
    }

    private record TestWorkflowStateMigrator(
            String workflowId,
            int fromVersion,
            int toVersion,
            UnaryOperator<Map<String, String>> migration) implements WorkflowStateMigrator {

        @Override
        public Map<String, String> migrate(Map<String, String> payload) {
            return migration.apply(payload);
        }
    }
}
