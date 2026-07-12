package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;
import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.memory.UserMemoryWriteResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CustomerServiceSkill 的单元测试。
 *
 * 这里不启动 Spring，也不调用真实 LLM。
 * 原因是：我们当前要验证的是“规则路由 + 多轮槽位填充”这部分纯业务逻辑，
 * 它不依赖外部网络，测试会更快、更稳定。
 */
class CustomerServiceSkillTest {

    /**
     * chatModel 当前在 CustomerServiceSkill 里只是预留字段，
     * 规则路由测试不会真正使用它，所以这里传 null 就可以。
     */
    private final UserMemoryStore userMemoryStore = new UserMemoryStore();
    private final CustomerServiceSkill skill = new CustomerServiceSkill(null, userMemoryStore);

    @Test
    void shouldAskOrderIdWhenReturnApplyMissesOrderId() {
        ConversationState state = new ConversationState("我要退货", "USER-001");

        AgentAction action = skill.nextAction(turn("conv-test", state));

        assertInstanceOf(AskUserAction.class, action);
        assertEquals("return_apply", state.getActiveIntent());
        assertEquals("order_id", state.getPendingSlot());
    }

    @Test
    void shouldAskReturnReasonAfterUserProvidesOrderId() {
        ConversationState state = new ConversationState("我要退货", "USER-001");
        skill.nextAction(turn("conv-test", state));

        // 模拟同一个 conversationId 下的下一轮消息。
        // 用户只说订单号，但 state 里还记得上一轮正在办理退货。
        state.startNewTurn("ORD-004");
        AgentAction action = skill.nextAction(turn("conv-test", state));

        assertInstanceOf(AskUserAction.class, action);
        assertEquals("ORD-004", state.getTaskState("order_id"));
        assertEquals("return_reason", state.getPendingSlot());
    }

    @Test
    void shouldCallReturnApplyAfterUserProvidesReason() {
        ConversationState state = new ConversationState("我要退货", "USER-001");
        skill.nextAction(turn("conv-test", state));

        state.startNewTurn("ORD-004");
        skill.nextAction(turn("conv-test", state));

        state.startNewTurn("质量问题");
        AgentAction action = skill.nextAction(turn("conv-test", state));

        CapabilityAction capabilityAction = assertInstanceOf(CapabilityAction.class, action);
        assertEquals("return_apply", capabilityAction.getCapability());
        assertEquals("ORD-004", capabilityAction.getArguments().get("order_id"));
        assertEquals("质量问题", capabilityAction.getArguments().get("reason"));
        assertNull(state.getPendingSlot());
    }

    @Test
    void shouldCallReturnApplyWhenMessageContainsOrderIdAndReason() {
        ConversationState state = new ConversationState("我想退这个订单 ORD-004，不想要了", "USER-001");

        AgentAction action = skill.nextAction(turn("conv-test", state));

        CapabilityAction capabilityAction = assertInstanceOf(CapabilityAction.class, action);
        assertEquals("return_apply", capabilityAction.getCapability());
        assertEquals("ORD-004", capabilityAction.getArguments().get("order_id"));
        assertEquals("不想要了", capabilityAction.getArguments().get("reason"));
    }

    @Test
    void shouldRoutePolicyQuestionToKnowledgeSearch() {
        ConversationState state = new ConversationState("超过七天还能退吗？", "USER-001");

        AgentAction action = skill.nextAction(turn("conv-test", state));

        CapabilityAction capabilityAction = assertInstanceOf(CapabilityAction.class, action);
        assertEquals("knowledge_search", capabilityAction.getCapability());
        assertEquals("knowledge_query", state.getActiveIntent());
        assertEquals("超过七天还能退吗？", capabilityAction.getArguments().get("query"));
    }

    @Test
    void shouldRouteHumanRequestToHandoffEvenWhenPendingSlotExists() {
        ConversationState state = new ConversationState("我要退货", "USER-001");
        skill.nextAction(turn("conv-test", state));

        // 上一轮 Agent 正在等待用户补充订单号。
        // 如果用户此时要求人工投诉，转人工优先级应该高于继续补槽。
        state.startNewTurn("我要转人工投诉");
        AgentAction action = skill.nextAction(turn("conv-test", state));

        HandoffAction handoffAction = assertInstanceOf(HandoffAction.class, action);
        assertEquals("COMPLAINT", handoffAction.getReason());
        assertEquals("handoff", state.getActiveIntent());
        assertNull(state.getPendingSlot());
    }

    @Test
    void shouldRememberProductPreference() {
        ConversationState state = new ConversationState("我喜欢蓝牙耳机", "USER-001");

        AgentAction action = skill.nextAction(turn("conv-memory", state));

        FinalAnswerAction finalAnswer = assertInstanceOf(FinalAnswerAction.class, action);
        assertTrue(finalAnswer.getAnswer().contains("蓝牙耳机"));
        assertEquals("user_memory_update", state.getActiveIntent());
        assertEquals("蓝牙耳机", userMemoryStore.getSnapshot("USER-001").getProductPreferences().get(0));
    }

    @Test
    void shouldUseProductPreferenceWhenUserAsksRecommendation() {
        userMemoryStore.rememberProductPreference("USER-001", "蓝牙耳机");
        ConversationState state = new ConversationState("给我推荐一下", "USER-001");
        TurnContext context = turn("conv-memory", state);

        AgentAction action = skill.nextAction(context);

        CapabilityAction capabilityAction = assertInstanceOf(CapabilityAction.class, action);
        assertEquals("product_search", capabilityAction.getCapability());
        assertEquals("蓝牙耳机", capabilityAction.getArguments().get("keyword"));
        assertEquals("蓝牙耳机", state.getTaskState("used_user_preference"));
        assertTrue(context.getTraceEvents().stream()
                .anyMatch(event -> "user_memory_used".equals(event.getEventType())));
    }

    @Test
    void legacySkillShouldNotEchoOrTraceRejectedPreference() {
        UserMemoryStore rejectingStore = mock(UserMemoryStore.class);
        when(rejectingStore.rememberProductPreferenceSafely(anyString(), anyString()))
                .thenReturn(UserMemoryWriteResult.rejected());
        CustomerServiceSkill rejectingSkill = new CustomerServiceSkill(null, rejectingStore);
        ConversationState state = new ConversationState("我喜欢蓝牙耳机", "USER-001");
        TurnContext context = turn("conv-sensitive-memory", state);

        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                rejectingSkill.nextAction(context)
        );

        assertTrue(answer.getAnswer().contains("保护隐私"));
        assertFalse(answer.getAnswer().contains("蓝牙耳机"));
        assertTrue(context.getTraceEvents().isEmpty(), "拒绝保存时不应创建包含原始偏好的 Trace");
    }

    /**
     * 为当前轮创建 TurnContext。
     *
     * 真实项目里这个动作由 CustomerServiceAgent 完成；
     * 单元测试里手动创建，可以更直接地验证新架构主链路。
     */
    private TurnContext turn(String conversationId, ConversationState state) {
        ChatRequest request = new ChatRequest(conversationId, state.getUserId(), state.getMessage());
        return new TurnContext(request, state);
    }
}
