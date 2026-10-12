package com.smartcs.smart_cs_agent.workflow.core;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowEligibilityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Workflow 的唯一选择入口：先恢复活跃任务，没有活跃任务时才做首次评分。
 *
 * <p>这是多轮“粘性”的关键。用户第二轮只回复 ORD-003 时，单看这句话无法知道是在退货，
 * 但 ConversationState.activeWorkflowId 能让 Router 继续找到 return_apply。
 * 粘性也有边界：活跃 Workflow 可以通过 acceptsMessage 声明“这条消息不属于我”，
 * Router 会取消它并对完整消息重新评分，避免悬挂的旧任务吞掉新问题。</p>
 */
@Component
public class WorkflowRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRouter.class);

    private final WorkflowRegistry registry;
    private final WorkflowRouterProperties properties;
    private final GlobalInterruptPolicy interruptPolicy;
    private final List<WorkflowEligibilityPolicy> eligibilityPolicies;

    /** 纯 Java 测试可以不安装渠道限制。 */
    public WorkflowRouter(WorkflowRegistry registry,
                          WorkflowRouterProperties properties,
                          GlobalInterruptPolicy interruptPolicy) {
        this(registry, properties, interruptPolicy, List.of());
    }

    /**
     * Spring 自动收集各渠道或租户的资格策略；Router 只负责通用选择流程。
     * 限制在评分之前生效，避免 Workflow 在 Capability 执行前就发出不适用的追问。
     */
    @Autowired
    public WorkflowRouter(WorkflowRegistry registry,
                          WorkflowRouterProperties properties,
                          GlobalInterruptPolicy interruptPolicy,
                          List<WorkflowEligibilityPolicy> eligibilityPolicies) {
        this.registry = registry;
        this.properties = properties;
        this.interruptPolicy = interruptPolicy;
        this.eligibilityPolicies = List.copyOf(eligibilityPolicies);
    }

    public WorkflowRouteResult route(ChatRequest request, ConversationState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(request.getMessage());
        RoutingContext routingContext = new RoutingContext(request, state);

        if (state.hasActiveNonTerminalWorkflow()) {
            // 明确切换任务时终止旧流程，然后对当前完整消息重新评分。
            // CANCEL/HANDOFF 仍交给活跃 Workflow，它最清楚如何清理自己的私有状态并生成回复。
            AgentWorkflow active = registry.require(state.getActiveWorkflowId());
            // 配置或渠道变化后，旧任务也必须重新满足当前资格限制。
            if (!isEligible(active, routingContext)) {
                LOGGER.info(
                        "资格策略拒绝恢复活跃 Workflow [{}]，取消后重新路由",
                        active.descriptor().getId()
                );
                state.markWorkflowCancelled();
            } else if (interrupt.getType() == GlobalInterruptType.SWITCH_TASK) {
                state.markWorkflowCancelled();
            } else if (interrupt.getType() == GlobalInterruptType.NONE
                    && !active.acceptsMessage(new RoutingContext(request, state))) {
                // 活跃 Workflow 拒接当前消息（如排查流程等补充信息时收到砍价问题）：
                // 与 SWITCH_TASK 一样终止旧流程并重新评分，否则悬挂数据会吞掉新任务。
                LOGGER.info("活跃 Workflow [{}] 拒接消息，取消后重新路由", active.descriptor().getId());
                state.markWorkflowCancelled();
            } else {
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

        List<ScoredWorkflow> scored = new ArrayList<>();
        for (AgentWorkflow workflow : registry.list()) {
            if (!isEligible(workflow, routingContext)) {
                LOGGER.debug(
                        "资格策略排除 Workflow [{}]",
                        workflow.descriptor().getId()
                );
                continue;
            }
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
            if (!isEligible(selected, routingContext)) {
                throw new IllegalStateException("Configured fallback Workflow is not eligible: "
                        + selected.descriptor().getId());
            }
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

    private boolean isEligible(AgentWorkflow workflow, RoutingContext context) {
        return eligibilityPolicies.stream().allMatch(policy -> policy.isEligible(workflow, context));
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
