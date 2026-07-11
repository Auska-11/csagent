package com.smartcs.smart_cs_agent.workflow.core;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Workflow 的唯一选择入口：先恢复活跃任务，没有活跃任务时才做首次评分。
 *
 * <p>这是多轮“粘性”的关键。用户第二轮只回复 ORD-003 时，单看这句话无法知道是在退货，
 * 但 ConversationState.activeWorkflowId 能让 Router 继续找到 return_apply。</p>
 */
@Component
public class WorkflowRouter {

    private final WorkflowRegistry registry;
    private final WorkflowRouterProperties properties;
    private final GlobalInterruptPolicy interruptPolicy;

    public WorkflowRouter(WorkflowRegistry registry,
                          WorkflowRouterProperties properties,
                          GlobalInterruptPolicy interruptPolicy) {
        this.registry = registry;
        this.properties = properties;
        this.interruptPolicy = interruptPolicy;
    }

    public WorkflowRouteResult route(ChatRequest request, ConversationState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(request.getMessage());

        if (state.hasActiveNonTerminalWorkflow()) {
            // 明确切换任务时终止旧流程，然后对当前完整消息重新评分。
            // CANCEL/HANDOFF 仍交给活跃 Workflow，它最清楚如何清理自己的私有状态并生成回复。
            if (interrupt.getType() == GlobalInterruptType.SWITCH_TASK) {
                state.markWorkflowCancelled();
            } else {
                AgentWorkflow active = registry.require(state.getActiveWorkflowId());
                state.startOrResumeWorkflow(active.descriptor().getId(), active.descriptor().getVersion());
                return new WorkflowRouteResult(
                        active,
                        true,
                        1.0,
                        "恢复非终态 Workflow；" + interrupt.getReason(),
                        List.of()
                );
            }
        }

        RoutingContext routingContext = new RoutingContext(request, state);
        List<ScoredWorkflow> scored = new ArrayList<>();
        for (AgentWorkflow workflow : registry.list()) {
            WorkflowMatchResult match = workflow.match(routingContext);
            scored.add(new ScoredWorkflow(workflow, match));
        }

        scored.sort(
                Comparator.comparingDouble((ScoredWorkflow item) -> item.match.getScore())
                        .reversed()
                        .thenComparing(
                                Comparator.comparingInt(
                                        (ScoredWorkflow item) -> item.workflow.descriptor().getPriority()
                                ).reversed()
                        )
                        .thenComparing(item -> item.workflow.descriptor().getId())
        );

        List<WorkflowCandidate> candidates = scored.stream()
                .map(item -> new WorkflowCandidate(
                        item.workflow.descriptor().getId(),
                        item.workflow.descriptor().getVersion(),
                        item.match.getScore(),
                        item.workflow.descriptor().getPriority(),
                        item.match.getReason()
                ))
                .toList();

        ScoredWorkflow best = scored.isEmpty() ? null : scored.get(0);
        AgentWorkflow selected;
        double selectedScore;
        String reason;
        if (best == null || best.match.getScore() < properties.getMinimumScore()) {
            selected = registry.require(properties.getFallbackWorkflowId());
            selectedScore = best == null ? 0.0 : best.match.getScore();
            reason = "最高匹配分低于阈值，使用通用 Workflow 兜底";
        } else {
            selected = best.workflow;
            selectedScore = best.match.getScore();
            reason = best.match.getReason();
        }

        state.startOrResumeWorkflow(selected.descriptor().getId(), selected.descriptor().getVersion());
        return new WorkflowRouteResult(selected, false, selectedScore, reason, candidates);
    }

    private static final class ScoredWorkflow {
        private final AgentWorkflow workflow;
        private final WorkflowMatchResult match;

        private ScoredWorkflow(AgentWorkflow workflow, WorkflowMatchResult match) {
            this.workflow = workflow;
            this.match = match;
        }
    }
}
