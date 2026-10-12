package com.smartcs.smart_cs_agent.workflow;

import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDescriptor;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowEligibilityPolicy;
import com.smartcs.smart_cs_agent.workflow.core.GlobalInterruptPolicy;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRegistry;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouteResult;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouter;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouterProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workflow Registry、首次评分和跨轮粘性路由测试。 */
class WorkflowRouterTest {

    @Test
    void shouldChooseHighestScoreThenPriorityAndExposeAllCandidates() {
        StubWorkflow generic = new StubWorkflow("generic", 10, 0.70);
        StubWorkflow specialist = new StubWorkflow("specialist", 100, 0.95);
        WorkflowRouter router = router(List.of(generic, specialist), "generic", 0.25);
        ConversationState state = new ConversationState("我要办理业务", "USER-001");

        WorkflowRouteResult result = router.route(
                new ChatRequest("conv-route", "USER-001", "我要办理业务"),
                state
        );

        assertEquals("specialist", result.getSelectedWorkflow().descriptor().getId());
        assertFalse(result.isResumed());
        assertEquals(2, result.getCandidates().size());
        assertEquals("specialist", state.getActiveWorkflowId());
    }

    @Test
    void shouldResumeWaitingWorkflowWithoutReMatchingLatestSlotOnlyMessage() {
        AtomicInteger activeMatchCalls = new AtomicInteger();
        AgentWorkflow active = new StubWorkflow("return_apply", 100, 0.0, activeMatchCalls);
        AgentWorkflow generic = new StubWorkflow("generic", 10, 1.0);
        WorkflowRouter router = router(List.of(active, generic), "generic", 0.25);

        ConversationState state = new ConversationState("我要退货", "USER-001");
        state.startOrResumeWorkflow("return_apply", "1.0.0");
        state.markWorkflowWaiting();
        state.startNewTurn("ORD-003");

        WorkflowRouteResult result = router.route(
                new ChatRequest("conv-sticky", "USER-001", "ORD-003"),
                state
        );

        assertEquals("return_apply", result.getSelectedWorkflow().descriptor().getId());
        assertTrue(result.isResumed());
        assertEquals(0, activeMatchCalls.get(), "恢复活跃任务时不应重新调用 match");
    }

    @Test
    void shouldCancelAndRescoreWhenActiveWorkflowDeclinesMessage() {
        AgentWorkflow waiting = new StubWorkflow("troubleshooting", 185, 0.0, true);
        StubWorkflow generic = new StubWorkflow("generic", 10, 1.0);
        WorkflowRouter router = router(List.of(waiting, generic), "generic", 0.25);

        ConversationState state = new ConversationState("耳机连不上", "USER-001");
        state.startOrResumeWorkflow("troubleshooting", "1.0.0");
        state.markWorkflowWaiting();
        state.startNewTurn("能便宜点吗");

        WorkflowRouteResult result = router.route(
                new ChatRequest("conv-decline", "USER-001", "能便宜点吗"),
                state
        );

        // 活跃 Workflow 拒接消息：取消后按首评逻辑重新路由，旧私有状态一并清空。
        assertFalse(result.isResumed());
        assertEquals("generic", result.getSelectedWorkflow().descriptor().getId());
        assertEquals("generic", state.getActiveWorkflowId());
        assertTrue(state.getWorkflowStateSnapshot("troubleshooting").isEmpty());
    }

    @Test
    void shouldUseConfiguredFallbackWhenAllScoresAreBelowThreshold() {
        StubWorkflow generic = new StubWorkflow("generic", 10, 0.10);
        StubWorkflow other = new StubWorkflow("other", 100, 0.20);
        WorkflowRouter router = router(List.of(generic, other), "generic", 0.50);

        WorkflowRouteResult result = router.route(
                new ChatRequest("conv-fallback", "USER-001", "完全不明确"),
                new ConversationState("完全不明确", "USER-001")
        );

        assertEquals("generic", result.getSelectedWorkflow().descriptor().getId());
        assertTrue(result.getReason().contains("低于阈值"));
    }

    @Test
    void shouldRouteACompletedConversationToANewWorkflow() {
        StubWorkflow returnApply = new StubWorkflow("return_apply", 100, 0.05);
        StubWorkflow product = new StubWorkflow("product_query", 80, 0.95);
        WorkflowRouter router = router(List.of(returnApply, product), "product_query", 0.25);

        ConversationState state = new ConversationState("我要退货", "USER-001");
        state.startOrResumeWorkflow("return_apply", "1.0.0");
        state.markWorkflowCompleted();
        state.startNewTurn("推荐一款耳机");

        WorkflowRouteResult result = router.route(
                new ChatRequest("conv-new-task", "USER-001", "推荐一款耳机"),
                state
        );

        // COMPLETED 是终态，不能像 WAITING_USER 一样继续粘住旧任务。
        assertFalse(result.isResumed());
        assertEquals("product_query", result.getSelectedWorkflow().descriptor().getId());
    }

    @Test
    void shouldRejectDuplicateWorkflowIdsAtStartup() {
        StubWorkflow first = new StubWorkflow("duplicate", 10, 0.5);
        StubWorkflow second = new StubWorkflow("duplicate", 20, 0.8);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new WorkflowRegistry(List.of(first, second), List.of())
        );

        assertTrue(error.getMessage().contains("Duplicate Workflow id"));
    }

    @Test
    void deniedWorkflowMustNotBeScoredOrResumed() {
        AtomicInteger matchCalls = new AtomicInteger();
        StubWorkflow blocked = new StubWorkflow("blocked", 100, 1.0, matchCalls);
        StubWorkflow generic = new StubWorkflow("generic", 10, 0.5);
        WorkflowEligibilityPolicy policy = (workflow, context) ->
                !"blocked".equals(workflow.descriptor().getId());
        WorkflowRouter router = router(List.of(blocked, generic), "generic", 0.25, List.of(policy));
        ChatRequest request = new ChatRequest("conv-policy", "USER-001", "办理业务");
        ConversationState state = new ConversationState("办理业务", "USER-001");

        assertEquals("generic", router.route(request, state).getSelectedWorkflow().descriptor().getId());
        state.startOrResumeWorkflow("blocked", "1.0.0");
        state.markWorkflowWaiting();
        WorkflowRouteResult resumed = router.route(request, state);
        assertFalse(resumed.isResumed());
        assertEquals("generic", resumed.getSelectedWorkflow().descriptor().getId());
        assertEquals(0, matchCalls.get());
        assertEquals(1, resumed.getCandidates().size());
    }

    @Test
    void everyPolicyMustAllowWorkflowAndFallbackCannotBypassRestrictions() {
        StubWorkflow generic = new StubWorkflow("generic", 10, 0.1);
        List<WorkflowEligibilityPolicy> policies = List.of(
                (workflow, context) -> true,
                (workflow, context) -> false);
        WorkflowRouter router = router(List.of(generic), "generic", 0.5, policies);
        ConversationState state = new ConversationState("不明确", "USER-001");
        assertThrows(IllegalStateException.class, () -> router.route(
                new ChatRequest("conv-blocked-fallback", "USER-001", "不明确"), state));
        assertFalse(state.hasActiveNonTerminalWorkflow());
    }

    private WorkflowRouter router(List<AgentWorkflow> workflows,
                                  String fallbackId,
                                  double minimumScore) {
        return router(workflows, fallbackId, minimumScore, List.of());
    }

    private WorkflowRouter router(List<AgentWorkflow> workflows,
                                  String fallbackId,
                                  double minimumScore,
                                  List<WorkflowEligibilityPolicy> policies) {
        WorkflowRegistry registry = new WorkflowRegistry(workflows, List.of());
        WorkflowRouterProperties properties = new WorkflowRouterProperties();
        properties.setFallbackWorkflowId(fallbackId);
        properties.setMinimumScore(minimumScore);
        return new WorkflowRouter(registry, properties, new GlobalInterruptPolicy(), policies);
    }

    private static class StubWorkflow implements AgentWorkflow {
        private final WorkflowDescriptor descriptor;
        private final double score;
        private final AtomicInteger matchCalls;
        private final boolean declinesResume;

        private StubWorkflow(String id, int priority, double score) {
            this(id, priority, score, new AtomicInteger());
        }

        private StubWorkflow(String id, int priority, double score, boolean declinesResume) {
            this(id, priority, score, new AtomicInteger(), declinesResume);
        }

        private StubWorkflow(String id, int priority, double score, AtomicInteger matchCalls) {
            this(id, priority, score, matchCalls, false);
        }

        private StubWorkflow(String id,
                             int priority,
                             double score,
                             AtomicInteger matchCalls,
                             boolean declinesResume) {
            this.descriptor = new WorkflowDescriptor(id, "1.0.0", "stub", priority);
            this.score = score;
            this.matchCalls = matchCalls;
            this.declinesResume = declinesResume;
        }

        @Override
        public WorkflowDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public WorkflowMatchResult match(RoutingContext context) {
            matchCalls.incrementAndGet();
            return new WorkflowMatchResult(score, "stub score");
        }

        @Override
        public boolean acceptsMessage(RoutingContext context) {
            return !declinesResume;
        }

        @Override
        public WorkflowDecision nextAction(TurnContext context) {
            return WorkflowDecision.next(new FinalAnswerAction("done"), "stub");
        }
    }
}
