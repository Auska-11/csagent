package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionFailureObservation;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionFlow;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.core.ActionHandlerNotFoundException;
import com.smartcs.smart_cs_agent.agent.handler.core.ActionHandlerRegistry;
import com.smartcs.smart_cs_agent.agent.handler.core.HandlerTraceSupport;
import com.smartcs.smart_cs_agent.skill.AgentRouteResult;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import com.smartcs.smart_cs_agent.skill.api.AgentSkillPackage;
import com.smartcs.smart_cs_agent.workflow.api.AgentWorkflow;
import com.smartcs.smart_cs_agent.workflow.api.WorkflowDecision;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowCandidate;
import com.smartcs.smart_cs_agent.workflow.core.WorkflowRouteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 的通用执行循环（Runtime Kernel）。
 *
 * <p><strong>现在只负责框架编排，不负责具体 Action 的执行。</strong></p>
 *
 * <p>一轮调用链：</p>
 * <pre>
 * TurnContext
 *   -> WorkflowRouter 选择或恢复 Workflow
 *   -> Workflow.nextAction() 只做决策
 *   -> ActionHandlerRegistry 找到插件 Handler
 *   -> Handler 执行 LLM / Capability / 追问 / 确认 / 转人工
 *   -> Runtime 根据统一 ActionExecutionResult 继续循环或结束
 * </pre>
 *
 * <p>为什么 Runtime 不再写 {@code instanceof}：新增一种 Action 时，只新增 Action 类和 Handler Bean；
 * Runtime、Router 和其他 Handler 都不用修改，避免这个核心类随着业务增长越来越大。</p>
 */
@Component
public class AgentRuntime {

    private static final int DEFAULT_MAX_ITERATIONS = 10;
    private static final long DEFAULT_TURN_TIMEOUT_MS = 30_000;

    /** 负责 Workflow 路由以及 Workflow -> Skill Package 绑定。 */
    private final SkillManager skillManager;

    /** 负责 Action Class -> ActionHandler 的插件分发。 */
    private final ActionHandlerRegistry actionHandlerRegistry;

    /**
     * 单轮最多执行多少个 Action。
     *
     * <p>它限制的是 Agent 循环步骤，不是 HTTP 秒数。即使某个扩展 Workflow 错误地一直返回 CONTINUE，
     * Runtime 也会在固定步数后安全终止，避免一个请求无限占用线程。</p>
     */
    private final int maxIterations;

    /**
     * 一整轮 Agent 执行最多占用的 wall-clock 时间。
     *
     * <p>步骤预算只能阻止“循环次数太多”，无法阻止某一步 LLM 或外部能力一直卡住；
     * 时间预算负责补上这个维度，并由 TurnContext 向所有 Handler 传递同一份剩余时间。</p>
     */
    private final long turnTimeoutMs;

    /**
     * 出站安全过滤：最终回复写回会话与下发给渠道前，拦截站外联系方式话术。
     *
     * <p>只拦“联系方式交换意图”而不拦平台名单词（如排查文档里的“微信不提醒”），
     * 详见 {@link OutboundSafetyFilter}。</p>
     */
    private final OutboundSafetyFilter outboundSafetyFilter;

    /** 入站对抗拦截：路由之前拒绝系统提示词索取、站外交易诱导、身份冒充数据索取与全量导出。 */
    private final AdversarialInputPolicy adversarialInputPolicy;

    /** 兼容纯 Java 测试的默认构造方式。 */
    public AgentRuntime(SkillManager skillManager, ActionHandlerRegistry actionHandlerRegistry) {
        this(
                skillManager,
                actionHandlerRegistry,
                DEFAULT_MAX_ITERATIONS,
                DEFAULT_TURN_TIMEOUT_MS
        );
    }

    /** 兼容已有可靠性测试：第三个参数仍然只表示最大 Action 步数。 */
    public AgentRuntime(SkillManager skillManager,
                        ActionHandlerRegistry actionHandlerRegistry,
                        int maxIterations) {
        this(skillManager, actionHandlerRegistry, maxIterations, DEFAULT_TURN_TIMEOUT_MS);
    }

    /** 兼容旧测试：不注入出站过滤时使用默认安全策略。 */
    public AgentRuntime(SkillManager skillManager,
                        ActionHandlerRegistry actionHandlerRegistry,
                        int maxIterations,
                        long turnTimeoutMs) {
        this(skillManager, actionHandlerRegistry, maxIterations, turnTimeoutMs,
                OutboundSafetyFilter.withDefaults(), AdversarialInputPolicy.disabled());
    }

    /**
     * Spring 生产构造函数；步骤和整轮时间两种预算都可配置，但都不能关闭。
     * 安全组件（出站过滤 + 入站闸门）必须显式注入，缺失即启动失败。
     */
    @Autowired
    public AgentRuntime(SkillManager skillManager,
                        ActionHandlerRegistry actionHandlerRegistry,
                        @Value("${smartcs.agent.max-iterations:10}") int maxIterations,
                        @Value("${smartcs.agent.turn-timeout-ms:30000}") long turnTimeoutMs,
                        OutboundSafetyFilter outboundSafetyFilter,
                        AdversarialInputPolicy adversarialInputPolicy) {
        this.skillManager = Objects.requireNonNull(skillManager, "skillManager cannot be null");
        this.actionHandlerRegistry = Objects.requireNonNull(
                actionHandlerRegistry,
                "actionHandlerRegistry cannot be null"
        );
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("smartcs.agent.max-iterations must be positive");
        }
        if (turnTimeoutMs <= 0) {
            throw new IllegalArgumentException("smartcs.agent.turn-timeout-ms must be positive");
        }
        this.maxIterations = maxIterations;
        this.turnTimeoutMs = turnTimeoutMs;
        this.outboundSafetyFilter = Objects.requireNonNull(
                outboundSafetyFilter,
                "outboundSafetyFilter cannot be null"
        );
        this.adversarialInputPolicy = Objects.requireNonNull(
                adversarialInputPolicy,
                "adversarialInputPolicy cannot be null"
        );
    }

    /** 兼容旧调用方：把 ConversationState 包装成 TurnContext。 */
    public String runTurn(ConversationState state) {
        ChatRequest request = new ChatRequest(
                "legacy-" + state.getUserId(),
                state.getUserId(),
                state.getMessage()
        );
        return runTurn(new TurnContext(request, state));
    }

    /**
     * 执行一轮对话，直到 Handler 要求等待用户、任务完成或超过最大循环步数。
     */
    public String runTurn(TurnContext context) {
        // 每次进入 Runtime 都重新开始本轮预算。ConversationState 可以跨轮恢复，时间预算不能跨轮继承。
        context.startTurnBudget(turnTimeoutMs);
        ConversationState state = context.getState();
        recordTurnStarted(context, state);

        // 入站对抗闸门先于一切路由与能力执行：命中即整轮固定话术拒绝，不进入任何 Workflow。
        AdversarialInputPolicy.Denial denial =
                adversarialInputPolicy.evaluate(context.getMessage());
        if (denial != null) {
            return finishSecurityDeniedTurn(context, denial);
        }

        // Multi-Agent 路由目前用于专家域 Trace；真正可执行选择由 WorkflowRouter 完成。
        AgentRouteResult agentRoute = skillManager.routeAgent(context.getMessage());
        context.addTrace("agent_routed", "Multi-Agent 路由完成", buildAgentRouteTraceData(agentRoute));

        WorkflowRouteResult workflowRoute = skillManager.routeWorkflow(context.getRequest(), state);
        AgentWorkflow workflow = workflowRoute.getSelectedWorkflow();
        context.addTrace(
                "workflow_routed",
                workflowRoute.isResumed() ? "恢复活跃 Workflow" : "完成首次 Workflow 路由",
                buildWorkflowRouteTraceData(workflowRoute)
        );
        context.addTrace("skill_selected", "选择当前客服 Workflow", Map.of(
                "skill", workflow.descriptor().getId(),
                "workflowVersion", workflow.descriptor().getVersion(),
                "routedAgent", agentRoute.getAgentName()
        ));

        AgentSkillPackage skillPackage = skillManager
                .findPackageForWorkflow(workflow.descriptor().getId())
                .orElse(null);
        recordSelectedSkillPackage(context, workflow, skillPackage);

        for (int i = 0; i < maxIterations; i++) {
            if (context.isTurnBudgetExceeded()) {
                return finishDeadlineExceededTurn(context, workflow);
            }
            context.increaseStepCount();

            // Workflow 只决定下一步，不执行外部调用。
            String statusBefore = workflowStatus(state);
            Map<String, Object> stateBefore = state.getActiveWorkflowStateSnapshot();
            WorkflowDecision decision = workflow.nextAction(context);
            AgentAction action = decision.getAction();
            recordWorkflowStateChangeIfNeeded(
                    context,
                    workflow,
                    decision,
                    statusBefore,
                    stateBefore
            );

            // Workflow 决策通常只做内存计算，但扩展实现也可能变慢。预算耗尽后不能再启动外部副作用。
            if (context.isTurnBudgetExceeded()) {
                return finishDeadlineExceededTurn(context, workflow);
            }

            ActionExecutionResult result = executeActionWithBoundary(
                    action,
                    decision,
                    context,
                    workflow,
                    skillPackage
            );
            if (result.shouldContinue()) {
                // 某个慢 Action 返回后再次检查，确保不会因为“已经进入本次循环”而多执行下一步。
                if (context.isTurnBudgetExceeded()) {
                    return finishDeadlineExceededTurn(context, workflow);
                }
                continue;
            }
            return finishTurn(context, workflow, result);
        }

        return finishTimedOutTurn(context, workflow, maxIterations);
    }

    /**
     * Action 插件统一异常边界。
     *
     * <p>具体 Handler 可以处理自己理解的业务/技术失败；任何遗漏异常都会在这里变成结构化
     * Observation 和安全回复，避免未知插件异常直接变成 HTTP 500 或泄露内部信息。</p>
     */
    private ActionExecutionResult executeActionWithBoundary(AgentAction action,
                                                            WorkflowDecision decision,
                                                            TurnContext context,
                                                            AgentWorkflow workflow,
                                                            AgentSkillPackage skillPackage) {
        String actionId = context.getRequest().getConversationId() + ":action-" + context.getStepCount();
        String handlerId = "unresolved";
        long startedAtNanos = System.nanoTime();
        try {
            handlerId = actionHandlerRegistry.handlerId(action);
            Map<String, Object> plannedData = new LinkedHashMap<>(actionHandlerRegistry.describe(action));
            plannedData.put("actionId", actionId);
            plannedData.put("handlerId", handlerId);
            plannedData.put("workflow", workflow.descriptor().getId());
            plannedData.put("decisionReason", decision.getDecisionReason());
            context.addTrace("action_planned", "Workflow 决定下一步 Action", plannedData);

            ActionExecutionResult result = actionHandlerRegistry.execute(
                    action,
                    new ActionHandlingContext(context, workflow, skillPackage)
            );
            context.addTrace("action_handled", "Action Handler 执行完成", Map.of(
                    "actionId", actionId,
                    "handlerId", handlerId,
                    "status", "SUCCESS",
                    "flow", result.getFlow().name(),
                    "durationMs", HandlerTraceSupport.elapsedMillis(startedAtNanos)
            ));
            return result;
        } catch (RuntimeException e) {
            String errorCode = e instanceof ActionHandlerNotFoundException
                    ? ActionHandlerNotFoundException.ERROR_CODE
                    : "ACTION_HANDLER_EXECUTION_FAILED";

            Map<String, Object> failedData = new LinkedHashMap<>();
            failedData.put("actionId", actionId);
            failedData.put("handlerId", handlerId);
            failedData.put("actionType", action.getClass().getName());
            failedData.put("status", "FAILED");
            failedData.put("errorCode", errorCode);
            failedData.put("durationMs", HandlerTraceSupport.elapsedMillis(startedAtNanos));
            failedData.put("exceptionType", e.getClass().getSimpleName());
            failedData.put("internalMessage", String.valueOf(e.getMessage()));
            context.addTrace("action_handler_failed", "Action Handler 执行失败，使用安全降级回复", failedData);

            context.addObservation("action_failure", new ActionFailureObservation(
                    errorCode,
                    action.getClass().getName(),
                    handlerId,
                    "Action handler execution failed"
            ));
            return ActionExecutionResult.failed(
                    "当前处理步骤暂时失败，为避免重复操作已经停止。请稍后重试，或请求人工客服。"
            );
        }
    }

    /**
     * 根据 Handler 的统一结果收尾。
     *
     * <p>这里判断的是流程控制枚举，不是具体 Action 类型，所以新增 Action 不会增加分支。</p>
     */
    private String finishTurn(TurnContext context,
                              AgentWorkflow workflow,
                              ActionExecutionResult result) {
        ConversationState state = context.getState();
        String answer = result.getAnswer();

        // 出站安全过滤先于 final_answer Trace 与会话记忆：三处必须看到同一份最终话术。
        // Trace 只记命中的模式，不回填原文片段，避免把联系方式换了个地方写进日志。
        OutboundSafetyFilter.Outcome outboundOutcome = outboundSafetyFilter.apply(answer);
        if (outboundOutcome.filtered()) {
            answer = outboundOutcome.text();
            context.addTrace(
                    "outbound_filtered",
                    "最终回复命中站外联系方式过滤，已整条替换为安全提示",
                    Map.of("matchedPatterns", outboundOutcome.matchedPatterns())
            );
        }

        // 所有“结束型” Handler 都由 Runtime 统一补 final_answer，保证自定义 Handler 不会漏掉核心 Trace。
        // WAITING_USER 已由 Ask/Confirm Handler 分别记录 ask_user 或 confirmation_requested。
        if (result.getFlow() != ActionFlow.WAITING_USER) {
            context.addTrace("final_answer", "Action Handler 返回最终回复", Map.of("answer", answer));
        }
        int summaryVersionBefore = state.getSummaryVersion();
        state.addAssistantMessage(answer);
        recordMemoryCompressionIfNeeded(context, summaryVersionBefore, state);

        if (result.getFlow() == ActionFlow.WAITING_USER) {
            state.markWorkflowWaiting();
            context.addTrace("workflow_waiting", "Workflow 暂停并等待下一轮用户输入", Map.of(
                    "workflow", workflow.descriptor().getId(),
                    "status", workflowStatus(state)
            ));
        } else if (result.getFlow() == ActionFlow.CANCELLED) {
            // CancelWorkflowAction 用结构化 flow 表达“用户主动取消”。
            // Runtime 只处理通用生命周期，不需要识别某个 Workflow 的内部 CANCELLED 枚举或回复文案。
            state.clearTaskProgress();
            state.markWorkflowCancelled();
            recordWorkflowTerminal(context, workflow, state);
        } else if (result.getFlow() == ActionFlow.FAILED) {
            // 未知插件异常不能伪装成正常完成。保留 Workflow 私有状态便于恢复快照和故障分析，
            // 但全局状态设为终态 FAILED，下一轮 Router 不会继续执行这条损坏流程。
            state.markWorkflowFailed();
            recordWorkflowTerminal(context, workflow, state);
        } else if (result.getFlow() == ActionFlow.HANDED_OFF) {
            state.clearTaskProgress();
            state.markWorkflowHandedOff();
            recordWorkflowTerminal(context, workflow, state);
        } else if (result.getFlow() == ActionFlow.COMPLETED) {
            state.clearTaskProgress();
            state.markWorkflowCompleted();
            recordWorkflowTerminal(context, workflow, state);
        } else {
            throw new IllegalStateException("Unexpected terminal Action flow: " + result.getFlow());
        }

        context.markDone(answer);
        return answer;
    }

    /**
     * 入站对抗闸门的整轮收尾。
     *
     * <p>语义与 {@link #finishTurn} 对齐（出站过滤 → final_answer → 会话记忆 → 记忆压缩 →
     * markDone），但有两个刻意差异：</p>
     * <ul>
     *     <li>不经过任何 Workflow，也就不改变 Workflow 生命周期状态——若闸门拦截的是
     *     活跃 Workflow 等待中的一轮输入（例如用户在退货流程中途发送站外交易诱导），
     *     该 Workflow 保持可恢复，用户回到正题后下一轮照常继续；</li>
     *     <li>Trace 使用 TurnOutcomeClassifier 已识别的 {@code guardrail_rejected} 事件
     *     （errorCode=ADVERSARIAL_INPUT_DENIED），监控侧自然归入 GUARDRAIL_REJECTED，
     *     不需要新增分类规则。话术是固定模板，不调用 LLM，故障注入下行为不变。</li>
     * </ul>
     */
    private String finishSecurityDeniedTurn(TurnContext context,
                                            AdversarialInputPolicy.Denial denial) {
        ConversationState state = context.getState();
        context.addTrace("guardrail_rejected", "入站对抗闸门命中，本轮直接拒绝且不调用任何能力", Map.of(
                "errorCode", "ADVERSARIAL_INPUT_DENIED",
                "rule", denial.ruleId(),
                "reason", denial.description()
        ));

        String answer = denial.refusal();
        OutboundSafetyFilter.Outcome outboundOutcome = outboundSafetyFilter.apply(answer);
        if (outboundOutcome.filtered()) {
            answer = outboundOutcome.text();
            context.addTrace(
                    "outbound_filtered",
                    "安全拒绝话术命中站外联系方式过滤，已整条替换为安全提示",
                    Map.of("matchedPatterns", outboundOutcome.matchedPatterns())
            );
        }

        context.addTrace("final_answer", "安全闸拒绝后返回固定话术", Map.of("answer", answer));
        int summaryVersionBefore = state.getSummaryVersion();
        state.addAssistantMessage(answer);
        recordMemoryCompressionIfNeeded(context, summaryVersionBefore, state);
        context.markDone(answer);
        return answer;
    }

    private String finishTimedOutTurn(TurnContext context,
                                      AgentWorkflow workflow,
                                      int maxIterations) {
        String answer = "当前处理步骤过多，已暂停。请换个说法再试一次。";
        ConversationState state = context.getState();
        context.addTrace("turn_timeout", "本轮执行超过最大步数", Map.of("maxIterations", maxIterations));

        int summaryVersionBefore = state.getSummaryVersion();
        state.addAssistantMessage(answer);
        recordMemoryCompressionIfNeeded(context, summaryVersionBefore, state);
        state.markWorkflowFailed();
        recordWorkflowTerminal(context, workflow, state);
        context.markDone(answer);
        return answer;
    }

    /**
     * 整轮 wall-clock 预算耗尽后的统一收尾。
     *
     * <p>这里把 Workflow 标记为 FAILED，而不是保留为可自动恢复状态。原因是超时发生时，
     * 某个只读调用可能仍在后台响应；对于副作用能力，Capability 层还会用结果未知语义和幂等键保护重试。
     * 下一轮应重新路由并由用户显式重试，不能在同一轮继续盲目前进。</p>
     */
    private String finishDeadlineExceededTurn(TurnContext context, AgentWorkflow workflow) {
        String answer = "当前处理超过本轮时间上限，已安全停止。请稍后重试，或请求人工客服。";
        ConversationState state = context.getState();
        context.addTrace("turn_deadline_exceeded", "本轮执行超过 wall-clock 总预算", Map.of(
                "budgetMs", context.getConfiguredTurnBudgetMs(),
                "remainingBudgetMs", context.remainingTurnBudgetMs(),
                "stepCount", context.getStepCount()
        ));

        int summaryVersionBefore = state.getSummaryVersion();
        state.addAssistantMessage(answer);
        recordMemoryCompressionIfNeeded(context, summaryVersionBefore, state);
        state.markWorkflowFailed();
        recordWorkflowTerminal(context, workflow, state);
        context.markDone(answer);
        return answer;
    }

    private void recordTurnStarted(TurnContext context, ConversationState state) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", context.getRequest().getConversationId());
        data.put("userId", context.getRequest().getUserId());
        data.put("message", context.getMessage());
        data.put("turnTimeoutMs", context.getConfiguredTurnBudgetMs());
        context.addTrace("turn_started", "开始处理用户本轮消息", data);
        context.addTrace("memory_loaded", "读取会话记忆", buildMemoryTraceData(state));
    }

    private void recordSelectedSkillPackage(TurnContext context,
                                            AgentWorkflow workflow,
                                            AgentSkillPackage skillPackage) {
        if (skillPackage == null) {
            context.addTrace(
                    "skill_package_selected",
                    "当前 Workflow 未绑定新版 Skill Package，使用 legacy Prompt 回滚路径",
                    Map.of("workflow", workflow.descriptor().getId(), "source", "legacy")
            );
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("skillId", skillPackage.getDefinition().getId());
        data.put("skillVersion", skillPackage.getDefinition().getVersion());
        data.put("workflow", skillPackage.getDefinition().getWorkflowId());
        data.put("source", skillPackage.getSourceDescription());
        data.put("allowedCapabilities", skillPackage.getDefinition().getAllowedCapabilities());
        context.addTrace("skill_package_loaded", "使用启动时已校验的 Skill Package 快照", data);
        context.addTrace("skill_package_selected", "为当前 Workflow 选择 Skill Package", data);
    }

    private void recordWorkflowStateChangeIfNeeded(TurnContext context,
                                                    AgentWorkflow workflow,
                                                    WorkflowDecision decision,
                                                    String statusBefore,
                                                    Map<String, Object> stateBefore) {
        ConversationState state = context.getState();
        String statusAfter = workflowStatus(state);
        Map<String, Object> stateAfter = state.getActiveWorkflowStateSnapshot();
        if (Objects.equals(statusBefore, statusAfter) && Objects.equals(stateBefore, stateAfter)) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("workflow", workflow.descriptor().getId());
        data.put("statusBefore", statusBefore);
        data.put("statusAfter", statusAfter);
        data.put("stateBefore", stateBefore);
        data.put("stateAfter", stateAfter);
        data.put("reason", decision.getDecisionReason());
        context.addTrace("workflow_state_transition", "Workflow 状态发生变化", data);
    }

    private void recordWorkflowTerminal(TurnContext context,
                                        AgentWorkflow workflow,
                                        ConversationState state) {
        context.addTrace("workflow_completed", "Workflow 进入终态", Map.of(
                "workflow", workflow.descriptor().getId(),
                "status", workflowStatus(state)
        ));
    }

    private Map<String, Object> buildWorkflowRouteTraceData(WorkflowRouteResult route) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("selectedWorkflow", route.getSelectedWorkflow().descriptor().getId());
        data.put("selectedVersion", route.getSelectedWorkflow().descriptor().getVersion());
        data.put("resumed", route.isResumed());
        data.put("selectedScore", route.getSelectedScore());
        data.put("reason", route.getReason());

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (WorkflowCandidate candidate : route.getCandidates()) {
            Map<String, Object> candidateData = new LinkedHashMap<>();
            candidateData.put("workflow", candidate.getWorkflowId());
            candidateData.put("version", candidate.getVersion());
            candidateData.put("score", candidate.getScore());
            candidateData.put("priority", candidate.getPriority());
            candidateData.put("reason", candidate.getReason());
            candidates.add(candidateData);
        }
        data.put("candidates", candidates);
        return data;
    }

    private Map<String, Object> buildAgentRouteTraceData(AgentRouteResult route) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("routeKey", route.getRouteKey());
        data.put("agentName", route.getAgentName());
        data.put("confidence", route.getConfidence());
        data.put("reason", route.getReason());
        return data;
    }

    private Map<String, Object> buildMemoryTraceData(ConversationState state) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activeIntent", state.getActiveIntent());
        data.put("pendingSlot", state.getPendingSlot());
        data.put("activeWorkflowId", state.getActiveWorkflowId());
        data.put("workflowStatus", workflowStatus(state));
        data.put("shortTermMessageCount", state.getMessages().size());
        data.put("totalMessageCount", state.getTotalMessageCount());
        data.put("summaryVersion", state.getSummaryVersion());
        data.put("hasSummary", state.hasSummaryMemory());
        return data;
    }

    private void recordMemoryCompressionIfNeeded(TurnContext context,
                                                 int summaryVersionBefore,
                                                 ConversationState state) {
        if (state.getSummaryVersion() <= summaryVersionBefore) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summaryVersion", state.getSummaryVersion());
        data.put("summaryUpdatedAt", state.getSummaryUpdatedAt());
        data.put("shortTermMessageCount", state.getMessages().size());
        data.put("summaryPreview", HandlerTraceSupport.preview(state.getSummaryMemory(), 180));
        context.addTrace("memory_compressed", "长会话已压缩为摘要记忆", data);
    }

    private String workflowStatus(ConversationState state) {
        return state.getWorkflowStatus() == null ? "NONE" : state.getWorkflowStatus().name();
    }

}
