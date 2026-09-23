package com.smartcs.smart_cs_agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStateSnapshot;
import com.smartcs.smart_cs_agent.agent.context.UnsupportedWorkflowStateSchemaException;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStateMigrationRegistry;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStateSnapshot;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import com.smartcs.smart_cs_agent.session.core.ConversationStateCodec;
import com.smartcs.smart_cs_agent.session.core.UnsupportedSessionSchemaException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ConversationState 显式快照、JSON 编解码和恢复测试。 */
class ConversationStatePersistenceTest {

    private final ConversationStateCodec codec = new ConversationStateCodec(new ObjectMapper());
    private final WorkflowStateMigrationRegistry migrationRegistry =
            new WorkflowStateMigrationRegistry(List.of());

    @Test
    void shouldRoundTripWaitingWorkflowWithoutPersistingRuntimeObjects() {
        ConversationState original = new ConversationState("我要退货", "USER-002");
        original.setActiveIntent("return_apply");
        original.setPendingSlot("return_reason");
        original.setTaskState("order_id", "ORD-003");
        original.setTaskState("last_capability_result", "runtime-secret");
        original.addObservation("capability", new Object());
        original.startOrResumeWorkflow("return_apply", "2.0.0");
        original.setWorkflowState("return_apply", "step", "WAITING_CONFIRMATION");
        original.setWorkflowState("return_apply", "orderId", "ORD-003");
        original.setWorkflowState("return_apply", "reason", "质量问题");
        original.markWorkflowWaiting();

        for (int i = 0; i < 5; i++) {
            original.addAssistantMessage("助手回复 " + i);
            original.startNewTurn("用户补充 " + i);
        }

        ConversationStateSnapshot before = original.toPersistenceSnapshot();
        String json = codec.encode(before);
        ConversationStateSnapshot decoded = codec.decode(
                json,
                ConversationStateSnapshot.CURRENT_SCHEMA_VERSION
        );
        ConversationState restored = ConversationState.restore(decoded);

        assertEquals(original.getMessage(), restored.getMessage());
        assertEquals(original.getMessages(), restored.getMessages());
        assertEquals(original.getTotalMessageCount(), restored.getTotalMessageCount());
        assertEquals(original.getSummaryMemory(), restored.getSummaryMemory());
        assertEquals(original.getSummaryVersion(), restored.getSummaryVersion());
        assertEquals(original.getSummaryUpdatedAt(), restored.getSummaryUpdatedAt());
        assertEquals("return_apply", restored.getActiveWorkflowId());
        assertEquals("2.0.0", restored.getWorkflowVersion());
        assertEquals(WorkflowStatus.WAITING_USER, restored.getWorkflowStatus());
        assertEquals(original.getWorkflowStartedAt(), restored.getWorkflowStartedAt());
        assertEquals(original.getWorkflowUpdatedAt(), restored.getWorkflowUpdatedAt());
        assertEquals("WAITING_CONFIRMATION", restored.getWorkflowState("return_apply", "step"));
        assertEquals("ORD-003", restored.getWorkflowState("return_apply", "orderId"));
        assertEquals("return_reason", restored.getPendingSlot());
        assertEquals("ORD-003", restored.getTaskState("order_id"));

        // Observation 和 Runtime scratchpad 不属于跨轮状态，恢复后必须为空。
        assertTrue(restored.getObservations().isEmpty());
        assertNull(restored.getTaskState("last_capability_result"));
        assertFalse(json.contains("runtime-secret"));
    }

    /**
     * 闲鱼议价会话的持久化往返：已报价格镜像（bargain_last_quote）与人工模式
     * （xianyu_manual_mode）都在 PERSISTENT_TASK_STATE_KEYS 里，必须跨 JSON 往返存活，
     * 否则重启后已同意的价格被改写、暂停被静默解除。
     */
    @Test
    void shouldRoundTripXianyuQuoteMirrorAndManualMode() {
        ConversationState original = new ConversationState("240卖吗", "USER-XY-TEST");
        original.startOrResumeWorkflow("bargain", "1.1.0");
        original.setWorkflowState("bargain", "step", "WAITING_BUYER");
        original.setWorkflowState("bargain", "lastQuote", "240");
        original.setWorkflowState("bargain", "buyerOffer", "240");
        original.setTaskState("bargain_last_quote", "240");
        original.setTaskState("xianyu_manual_mode", "true");
        original.setTaskState("last_llm_purpose", "response_generation");
        original.markWorkflowWaiting();

        // 卖家接管后人工确认成交：模拟 HANDED_OFF 清空 bargain 命名空间，只留 taskState 镜像。
        original.setWorkflowState("bargain", "lastQuote", null);
        original.setWorkflowState("bargain", "buyerOffer", null);
        original.setWorkflowState("bargain", "step", null);

        ConversationStateSnapshot snapshot = original.toPersistenceSnapshot();
        String json = codec.encode(snapshot);
        ConversationStateSnapshot decoded = codec.decode(
                json,
                ConversationStateSnapshot.CURRENT_SCHEMA_VERSION
        );
        ConversationState restored = ConversationState.restore(decoded);

        assertEquals("240", restored.getTaskState("bargain_last_quote"),
                "报价镜像必须经 JSON 编解码存活");
        assertEquals("true", restored.getTaskState("xianyu_manual_mode"),
                "人工模式必须经 JSON 编解码存活");
        assertTrue(json.contains("bargain_last_quote"));
        assertNull(restored.getTaskState("last_llm_purpose"), "运行期 taskState 不应持久化");
        // 恢复后的镜像要能被下一轮议价当作最近一口价读取（getLastQuote 回落路径）。
        original.setWorkflowState("bargain", "step", "WAITING_BUYER");
        ConversationState rehydrated = ConversationState.restore(decoded);
        rehydrated.startOrResumeWorkflow("bargain", "1.1.0");
        rehydrated.setWorkflowState("bargain", "lastQuote", rehydrated.getTaskState("bargain_last_quote"));
        assertEquals("240", rehydrated.getWorkflowState("bargain", "lastQuote"));
    }

    @Test
    void shouldRejectUnknownConversationSchemaVersion() {
        ConversationStateSnapshot current = new ConversationState(
                "hello",
                "USER-001"
        ).toPersistenceSnapshot();
        ConversationStateSnapshot future = copyWith(
                current,
                ConversationStateSnapshot.CURRENT_SCHEMA_VERSION + 1,
                current.workflowStates()
        );

        assertThrows(UnsupportedSessionSchemaException.class, () -> codec.encode(future));
    }

    @Test
    void shouldDecodeWorkflowVersionMetadataAndLetMigrationRegistryRejectUnknownFutureVersion() {
        ConversationState state = new ConversationState("我要退货", "USER-001");
        state.startOrResumeWorkflow("return_apply", "2.0.0");
        state.setWorkflowState("return_apply", "step", "WAITING_ORDER_ID");
        ConversationStateSnapshot current = state.toPersistenceSnapshot();

        Map<String, WorkflowStateSnapshot> workflows = new LinkedHashMap<>(current.workflowStates());
        workflows.put(
                "return_apply",
                new WorkflowStateSnapshot(
                        WorkflowStateSnapshot.BASE_SCHEMA_VERSION + 1,
                        Map.of("step", "WAITING_ORDER_ID")
                )
        );
        ConversationStateSnapshot future = copyWith(
                current,
                current.schemaVersion(),
                workflows
        );

        String json = codec.encode(future);
        ConversationStateSnapshot decoded = codec.decode(
                json,
                ConversationStateSnapshot.CURRENT_SCHEMA_VERSION
        );

        assertEquals(2, decoded.workflowStates().get("return_apply").schemaVersion());
        assertThrows(
                UnsupportedWorkflowStateSchemaException.class,
                () -> migrationRegistry.restore(decoded)
        );
    }

    @Test
    void shouldRejectUnsupportedWorkflowPayloadObject() {
        ConversationState state = new ConversationState("测试", "USER-001");
        state.startOrResumeWorkflow("custom", "1.0.0");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> state.setWorkflowState("custom", "unsafe", new Object())
        );

        assertTrue(error.getMessage().contains("custom.unsafe"));
    }

    private ConversationStateSnapshot copyWith(
            ConversationStateSnapshot source,
            int schemaVersion,
            Map<String, WorkflowStateSnapshot> workflowStates) {
        return new ConversationStateSnapshot(
                schemaVersion,
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
}
