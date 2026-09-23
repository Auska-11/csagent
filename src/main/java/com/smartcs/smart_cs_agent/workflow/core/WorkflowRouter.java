package com.smartcs.smart_cs_agent.workflow.core;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuItemFactService;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuScenarioProperties;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.RoutingContext;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowMatchResult;
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
    private final XianyuItemFactService xianyuItemFactService;
    private final XianyuScenarioProperties xianyuScenarioProperties;

    /** 兼容旧测试：不注入闲鱼场景时行为与此前完全一致。 */
    public WorkflowRouter(WorkflowRegistry registry,
                          WorkflowRouterProperties properties,
                          GlobalInterruptPolicy interruptPolicy) {
        this(registry, properties, interruptPolicy, null, null);
    }

    /**
     * 生产构造函数：注入闲鱼场景配置后，闲鱼会话在评分阶段就排除模拟商城流程。
     *
     * <p>边界必须放在 Router 评分层而不是 Capability 执行层：OrderQueryWorkflow 在
     * Capability 被拦之前就会先 AskUser“请提供 ORD-000 订单号”，真实买家会先收到
     * 一句要求演示订单号的追问——这在真实渠道里无法向买家交代。被排除的流程不参与
     * 打分，消息自然落到闲鱼可用的流程或通用兜底。</p>
     */
    @Autowired
    public WorkflowRouter(WorkflowRegistry registry,
                          WorkflowRouterProperties properties,
                          GlobalInterruptPolicy interruptPolicy,
                          XianyuItemFactService xianyuItemFactService,
                          XianyuScenarioProperties xianyuScenarioProperties) {
        this.registry = registry;
        this.properties = properties;
        this.interruptPolicy = interruptPolicy;
        this.xianyuItemFactService = xianyuItemFactService;
        this.xianyuScenarioProperties = xianyuScenarioProperties;
    }

    public WorkflowRouteResult route(ChatRequest request, ConversationState state) {
        GlobalInterruptDecision interrupt = interruptPolicy.evaluate(request.getMessage());

        if (state.hasActiveNonTerminalWorkflow()) {
            // 明确切换任务时终止旧流程，然后对当前完整消息重新评分。
            // CANCEL/HANDOFF 仍交给活跃 Workflow，它最清楚如何清理自己的私有状态并生成回复。
            AgentWorkflow active = registry.require(state.getActiveWorkflowId());
            // 排除约束必须同样管住粘性恢复：服务器保留的旧会话可能还挂着升级部署前
            // 未结束的 order_query/return_apply 等演示流程，照常恢复会让真实买家
            // 收到“请提供 ORD-000 演示订单号”这类追问。取消后落穿到首次评分，
            // 由闲鱼可用的 Workflow 或兜底接手当前消息。
            if (isXianyuSession(request) && isExcludedForXianyu(active)) {
                LOGGER.info(
                        "闲鱼会话拒绝恢复被排除的活跃 Workflow [{}]，取消后重新路由",
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

        RoutingContext routingContext = new RoutingContext(request, state);
        boolean xianyuSession = isXianyuSession(request);
        List<ScoredWorkflow> scored = new ArrayList<>();
        for (AgentWorkflow workflow : registry.list()) {
            if (xianyuSession && isExcludedForXianyu(workflow)) {
                LOGGER.debug(
                        "闲鱼会话排除模拟商城 Workflow [{}]（smartcs.xianyu.scenario.excluded-workflows）",
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

    private boolean isXianyuSession(ChatRequest request) {
        if (xianyuItemFactService == null || xianyuScenarioProperties == null) {
            return false;
        }
        return xianyuItemFactService.isXianyuConversation(
                request.getTenantId(),
                request.getConversationId()
        );
    }

    private boolean isExcludedForXianyu(AgentWorkflow workflow) {
        return xianyuScenarioProperties.isWorkflowExcluded(workflow.descriptor().getId());
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
