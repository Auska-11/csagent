package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationMemorySnapshot;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStateSnapshot;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStateMigrationRegistry;
import com.smartcs.smart_cs_agent.memory.MemorySafetyProperties;
import com.smartcs.smart_cs_agent.session.memory.InMemorySessionStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话记忆测试。
 *
 * 这里不启动 Spring，也不调用 LLM。
 * 我们只验证 ConversationState 自己的记忆规则：
 * - 短期消息是否会被限制在窗口内
 * - 较早消息是否会被压缩成摘要
 * - 任务槽位是否能进入记忆快照
 */
class ConversationStateMemoryTest {

    @Test
    void shouldCompressOldMessagesWhenConversationIsLong() {
        ConversationState state = new ConversationState("第 1 轮用户消息", "USER-001");

        for (int i = 1; i <= 7; i++) {
            state.addAssistantMessage("第 " + i + " 轮助手回复");
            state.startNewTurn("第 " + (i + 1) + " 轮用户消息");
        }

        assertTrue(state.hasSummaryMemory());
        assertTrue(state.getSummaryMemory().contains("已归档"));
        assertTrue(state.getMessages().size() <= state.getShortTermMessageLimit());
        assertEquals(15, state.getTotalMessageCount());
        assertTrue(state.getSummaryVersion() > 0);
    }

    @Test
    void shouldExposeTaskMemorySnapshot() {
        ConversationState state = new ConversationState("我要退货", "USER-001");
        state.setActiveIntent("return_apply");
        state.setPendingSlot("return_reason");
        state.setTaskState("order_id", "ORD-004");
        state.setTaskState("last_llm_purpose", "response_generation");

        ConversationMemorySnapshot snapshot = state.toMemorySnapshot("conv-memory");

        assertEquals("conv-memory", snapshot.getConversationId());
        assertEquals("USER-001", snapshot.getUserId());
        assertEquals("return_apply", snapshot.getActiveIntent());
        assertEquals("return_reason", snapshot.getPendingSlot());
        assertEquals("ORD-004", snapshot.getTaskMemory().get("order_id"));

        // Runtime 内部临时字段不应该出现在对外 Memory 面板里。
        assertFalse(snapshot.getTaskMemory().containsKey("last_llm_purpose"));
    }

    @Test
    void shouldRedactAndBoundGeneratedSummary() {
        ConversationState state = new ConversationState(
                "手机号 13800138000，我喜欢蓝牙耳机",
                "USER-001",
                120
        );

        for (int i = 0; i < 12; i++) {
            state.addAssistantMessage("请不要记录 buyer@example.com，第 " + i + " 次回复");
            state.startNewTurn("继续咨询蓝牙耳机，第 " + i + " 轮");
        }

        String summary = state.getSummaryMemory();
        assertTrue(summary.contains("[REDACTED]"));
        assertFalse(summary.contains("13800138000"));
        assertFalse(summary.contains("buyer@example.com"));
        assertTrue(summary.codePointCount(0, summary.length()) <= 120);
        assertEquals(120, state.getSummaryMemoryMaxLength());
    }

    @Test
    void shouldSanitizeOversizedLegacySummaryDuringRestore() {
        ConversationStateSnapshot base = new ConversationState(
                "hello",
                "USER-001"
        ).toPersistenceSnapshot();
        String legacySummary = "旧摘要手机号 13800138000 " + "很长内容".repeat(30);
        ConversationStateSnapshot legacy = copyWithSummary(base, legacySummary);

        ConversationState restored = new WorkflowStateMigrationRegistry(List.of()).restore(legacy, 80);

        assertFalse(restored.getSummaryMemory().contains("13800138000"));
        assertTrue(restored.getSummaryMemory().codePointCount(
                0,
                restored.getSummaryMemory().length()
        ) <= 80);
    }

    @Test
    void conversationStoreShouldApplyConfiguredSummaryLimitToNewAndRestoredState() {
        MemorySafetyProperties properties = new MemorySafetyProperties();
        properties.setSummaryMaxLength(64);
        InMemorySessionStore backend = new InMemorySessionStore();
        ConversationStore store = new ConversationStore(
                backend,
                new WorkflowStateMigrationRegistry(List.of()),
                properties
        );
        ChatRequest request = new ChatRequest("summary-config", "USER-001", "hello");

        ConversationState created = store.getOrCreate(request);
        ConversationState restored = store.get(
                ConversationKey.defaultTenant("USER-001", "summary-config")
        );

        assertEquals(64, created.getSummaryMemoryMaxLength());
        assertEquals(64, restored.getSummaryMemoryMaxLength());
    }

    private ConversationStateSnapshot copyWithSummary(
            ConversationStateSnapshot source,
            String summaryMemory) {
        return new ConversationStateSnapshot(
                source.schemaVersion(),
                source.userId(),
                source.message(),
                source.messages(),
                source.activeIntent(),
                source.taskState(),
                summaryMemory,
                source.totalMessageCount(),
                source.summaryVersion(),
                source.summaryUpdatedAt(),
                source.activeWorkflowId(),
                source.workflowVersion(),
                source.workflowStatus(),
                source.workflowStartedAt(),
                source.workflowUpdatedAt(),
                source.workflowStates()
        );
    }
}
