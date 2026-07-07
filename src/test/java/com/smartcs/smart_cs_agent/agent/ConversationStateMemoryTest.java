package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ConversationMemorySnapshot;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import org.junit.jupiter.api.Test;

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
}
