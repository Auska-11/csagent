package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.contracts.UserProfileSummary;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.userprofile.UserProfileStep;
import com.smartcs.smart_cs_agent.workflow.userprofile.UserProfileWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UserProfileWorkflow 的资料查询、偏好读写和认证测试。 */
class UserProfileWorkflowTest {

    private UserMemoryStore userMemoryStore;
    private UserProfileWorkflow workflow;

    @BeforeEach
    void setUp() {
        userMemoryStore = new UserMemoryStore();
        workflow = new UserProfileWorkflow(new GlobalInterruptPolicy(), userMemoryStore);
    }

    @Test
    void shouldLookupAuthenticatedProfileWithoutUserControlledArguments() {
        ConversationState state = startedState("查看我的个人信息", "USER-001");
        TurnContext context = authenticatedTurn(state, "查看我的个人信息", "USER-001");

        CapabilityAction lookup = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(context).getAction()
        );
        assertEquals(CapabilityIds.USER_LOOKUP, lookup.getCapability());
        assertTrue(lookup.getArguments().isEmpty());

        context.addObservation("capability_result", success(new UserProfileSummary(
                "USER-001",
                "张三",
                "13812341234"
        )));
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("张三"));
        assertTrue(answer.getAnswer().contains("138****1234"));
        assertFalse(answer.getAnswer().contains("13812341234"));
        assertStep(state, UserProfileStep.COMPLETED);
    }

    @Test
    void shouldRequireAuthenticationForProfileAndPreferenceWrites() {
        ConversationState profileState = startedState("查看我的信息", "USER-001");
        ConversationState preferenceState = startedState("我喜欢蓝牙耳机", "USER-001");

        FinalAnswerAction profile = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(unverifiedTurn(profileState, "查看我的信息", "USER-001")).getAction()
        );
        FinalAnswerAction preference = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(unverifiedTurn(preferenceState, "我喜欢蓝牙耳机", "USER-001")).getAction()
        );

        assertTrue(profile.getAnswer().contains("需要先登录"));
        assertTrue(preference.getAnswer().contains("需要先登录"));
        assertFalse(userMemoryStore.getSnapshot("USER-001").hasProductPreferences());
    }

    @Test
    void shouldWriteAndReadAuthenticatedProductPreferences() {
        ConversationState writeState = startedState("我喜欢蓝牙耳机", "USER-001");
        TurnContext writeTurn = authenticatedTurn(writeState, "我喜欢蓝牙耳机", "USER-001");

        FinalAnswerAction written = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(writeTurn).getAction()
        );
        assertTrue(written.getAnswer().contains("记住了"));
        assertEquals(
                "蓝牙耳机",
                userMemoryStore.getSnapshot("USER-001").getProductPreferences().get(0)
        );
        assertTrue(writeTurn.getTraceEvents().stream()
                .anyMatch(event -> "user_memory_updated".equals(event.getEventType())));

        ConversationState readState = startedState("查看我的偏好", "USER-001");
        FinalAnswerAction read = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(authenticatedTurn(readState, "查看我的偏好", "USER-001")).getAction()
        );
        assertTrue(read.getAnswer().contains("蓝牙耳机"));
    }

    @Test
    void shouldAskForSpecificPreferenceAcrossTurns() {
        ConversationState state = startedState("我有偏好", "USER-001");

        AskUserAction ask = assertInstanceOf(
                AskUserAction.class,
                workflow.nextAction(authenticatedTurn(state, "我有偏好", "USER-001")).getAction()
        );
        assertTrue(ask.getQuestion().contains("具体喜欢"));
        assertStep(state, UserProfileStep.WAITING_PREFERENCE);

        state.startNewTurn("智能手表");
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(authenticatedTurn(state, "智能手表", "USER-001")).getAction()
        );
        assertTrue(answer.getAnswer().contains("智能手表"));
    }

    @Test
    void shouldHandoffWhileWaitingForPreference() {
        ConversationState state = startedState("我有偏好", "USER-001");
        workflow.nextAction(authenticatedTurn(state, "我有偏好", "USER-001"));
        state.startNewTurn("我要人工客服");

        HandoffAction handoff = assertInstanceOf(
                HandoffAction.class,
                workflow.nextAction(authenticatedTurn(state, "我要人工客服", "USER-001")).getAction()
        );

        assertEquals("USER_REQUESTED_HUMAN", handoff.getReason());
        assertStep(state, UserProfileStep.CANCELLED);
    }

    private ConversationState startedState(String message, String userId) {
        ConversationState state = new ConversationState(message, userId);
        state.startOrResumeWorkflow(UserProfileWorkflow.ID, UserProfileWorkflow.VERSION);
        return state;
    }

    private TurnContext authenticatedTurn(ConversationState state, String message, String userId) {
        return new TurnContext(
                new ChatRequest(
                        "conv-user-profile",
                        userId,
                        message,
                        AuthenticatedPrincipal.authenticated(userId, Set.of("CUSTOMER"))
                ),
                state
        );
    }

    private TurnContext unverifiedTurn(ConversationState state, String message, String userId) {
        return new TurnContext(
                new ChatRequest("conv-user-profile", userId, message),
                state
        );
    }

    private CapabilityObservation success(UserProfileSummary profile) {
        return new CapabilityObservation(
                CapabilityIds.USER_LOOKUP,
                CapabilityOutcome.SUCCESS,
                "ok",
                profile,
                null,
                "test-provider",
                false
        );
    }

    private void assertStep(ConversationState state, UserProfileStep expected) {
        assertEquals(
                expected.name(),
                state.getWorkflowState(UserProfileWorkflow.ID, "step")
        );
    }
}
