package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.action.CapabilityAction;
import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.action.HandoffAction;
import com.smartcs.smart_cs_agent.agent.context.CapabilityObservation;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.capability.api.CapabilityIds;
import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSearchResult;
import com.smartcs.smart_cs_agent.capability.api.contracts.ProductSummary;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.productadvisory.ProductAdvisoryStep;
import com.smartcs.smart_cs_agent.workflow.productadvisory.ProductAdvisoryWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ProductAdvisoryWorkflow 的纯状态机与长期偏好读取测试。 */
class ProductAdvisoryWorkflowTest {

    private UserMemoryStore userMemoryStore;
    private ProductAdvisoryWorkflow workflow;

    @BeforeEach
    void setUp() {
        userMemoryStore = new UserMemoryStore();
        workflow = new ProductAdvisoryWorkflow(new GlobalInterruptPolicy(), userMemoryStore);
    }

    @Test
    void shouldSearchSpecificProductAndFormatStableContract() {
        ConversationState state = startedState("蓝牙耳机多少钱，有货吗", "USER-001");
        TurnContext context = turn(state, "蓝牙耳机多少钱，有货吗", "USER-001");

        CapabilityAction search = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(context).getAction()
        );
        assertEquals(CapabilityIds.PRODUCT_SEARCH, search.getCapability());
        assertEquals("耳机", search.getArguments().get("keyword"));

        context.addObservation("capability_result", success(new ProductSearchResult(
                "耳机",
                List.of(headset())
        )));
        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("蓝牙耳机 Pro Max"));
        assertTrue(answer.getAnswer().contains("599 元"));
        assertTrue(answer.getAnswer().contains("100 件"));
        assertTrue(answer.getAnswer().contains("主动降噪"));
        assertTrue(answer.getAnswer().contains("实际下单页面"));
        assertStep(state, ProductAdvisoryStep.COMPLETED);
    }

    @Test
    void shouldAskForMissingProductThenResumeAcrossTurns() {
        ConversationState state = startedState("这个多少钱", "USER-001");

        WorkflowDecision first = workflow.nextAction(turn(state, "这个多少钱", "USER-001"));
        assertInstanceOf(AskUserAction.class, first.getAction());
        assertStep(state, ProductAdvisoryStep.WAITING_KEYWORD);

        state.startNewTurn("蓝牙耳机");
        CapabilityAction search = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(turn(state, "蓝牙耳机", "USER-001")).getAction()
        );

        assertEquals("耳机", search.getArguments().get("keyword"));
        assertStep(state, ProductAdvisoryStep.SEARCHING);
    }

    @Test
    void shouldUseLatestLongTermPreferenceForGenericRecommendation() {
        userMemoryStore.rememberProductPreference("USER-001", "蓝牙耳机");
        userMemoryStore.rememberProductPreference("USER-001", "智能手表");
        ConversationState state = startedState("给我推荐一下", "USER-001");
        TurnContext context = turn(state, "给我推荐一下", "USER-001");

        CapabilityAction search = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(context).getAction()
        );

        assertEquals("智能手表", search.getArguments().get("keyword"));
        assertEquals(
                "智能手表",
                state.getWorkflowState(ProductAdvisoryWorkflow.ID, "usedPreference")
        );
        assertTrue(context.getTraceEvents().stream()
                .anyMatch(event -> "user_memory_used".equals(event.getEventType())));
    }

    @Test
    void shouldSearchWholeCatalogWhenRecommendationHasNoPreference() {
        ConversationState state = startedState("有什么商品推荐", "USER-001");

        CapabilityAction search = assertInstanceOf(
                CapabilityAction.class,
                workflow.nextAction(turn(state, "有什么商品推荐", "USER-001")).getAction()
        );

        assertEquals("", search.getArguments().get("keyword"));
    }

    @Test
    void shouldExplainEmptyCatalogResultWithoutInventingProduct() {
        ConversationState state = startedState("推荐耳机", "USER-001");
        TurnContext context = turn(state, "推荐耳机", "USER-001");
        workflow.nextAction(context);
        context.addObservation("capability_result", success(new ProductSearchResult("耳机", List.of())));

        FinalAnswerAction answer = assertInstanceOf(
                FinalAnswerAction.class,
                workflow.nextAction(context).getAction()
        );

        assertTrue(answer.getAnswer().contains("暂时没有找到"));
        assertTrue(answer.getAnswer().contains("耳机"));
    }

    @Test
    void shouldLeavePreferenceWritesAndPoliciesToTheirOwningWorkflows() {
        ConversationState preferenceState = new ConversationState("我喜欢蓝牙耳机", "USER-001");
        ConversationState warrantyState = new ConversationState("蓝牙耳机保修多久", "USER-001");

        assertEquals(
                0.0,
                workflow.match(new RoutingContext(
                        new ChatRequest("conv-product", "USER-001", "我喜欢蓝牙耳机"),
                        preferenceState
                )).getScore()
        );
        assertEquals(
                0.0,
                workflow.match(new RoutingContext(
                        new ChatRequest("conv-product", "USER-001", "蓝牙耳机保修多久"),
                        warrantyState
                )).getScore()
        );
    }

    @Test
    void shouldHandoffWhileWaitingForProductKeyword() {
        ConversationState state = startedState("这个多少钱", "USER-001");
        workflow.nextAction(turn(state, "这个多少钱", "USER-001"));
        state.startNewTurn("我要人工客服");

        HandoffAction handoff = assertInstanceOf(
                HandoffAction.class,
                workflow.nextAction(turn(state, "我要人工客服", "USER-001")).getAction()
        );

        assertEquals("USER_REQUESTED_HUMAN", handoff.getReason());
        assertStep(state, ProductAdvisoryStep.CANCELLED);
    }

    private ConversationState startedState(String message, String userId) {
        ConversationState state = new ConversationState(message, userId);
        state.startOrResumeWorkflow(ProductAdvisoryWorkflow.ID, ProductAdvisoryWorkflow.VERSION);
        return state;
    }

    private TurnContext turn(ConversationState state, String message, String userId) {
        return new TurnContext(
                new ChatRequest("conv-product", userId, message),
                state
        );
    }

    private CapabilityObservation success(ProductSearchResult data) {
        return new CapabilityObservation(
                CapabilityIds.PRODUCT_SEARCH,
                CapabilityOutcome.SUCCESS,
                "ok",
                data,
                null,
                "test-provider",
                false
        );
    }

    private ProductSummary headset() {
        return new ProductSummary(
                "PROD-001",
                "蓝牙耳机 Pro Max",
                599,
                100,
                "支持主动降噪，续航30小时，蓝牙5.3"
        );
    }

    private void assertStep(ConversationState state, ProductAdvisoryStep expected) {
        assertEquals(
                expected.name(),
                state.getWorkflowState(ProductAdvisoryWorkflow.ID, "step")
        );
    }
}
