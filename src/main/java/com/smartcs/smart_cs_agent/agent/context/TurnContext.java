package com.smartcs.smart_cs_agent.agent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 当前这一轮 Agent 执行上下文。
 *
 * 【为什么需要 TurnContext？】
 * ChatRequest 只表示“用户这次发了什么”；
 * ConversationState 表示“这个会话之前进行到哪里”；
 * TurnContext 则表示“这一轮执行过程中发生了什么”。
 *
 * 例如一轮对话中可能会发生：
 * - Skill 判断下一步 Action
 * - Runtime 调用工具
 * - Runtime 调用 LLM
 * - 产生本轮最终回复
 *
 * 这些信息只属于当前这一轮，不应该长期塞在 ConversationState 里。
 * 后续做 TraceEvent 时，也会优先挂在 TurnContext 上。
 */
public class TurnContext {

    /** 当前这一轮请求：保存 conversationId、userId、message */
    private final ChatRequest request;

    /** 跨轮会话状态：保存 activeIntent、任务槽位、历史消息等 */
    private final ConversationState state;

    /** 本轮执行过程中产生的观察结果，例如工具结果、LLM 结果 */
    private final List<Observation> observations;

    /** 本轮执行轨迹：记录每一步 Action、工具调用、LLM 调用等过程 */
    private final List<TraceEvent> traceEvents;

    /** 本轮已经执行了几步 Action，用于防止死循环和后续 Trace 展示 */
    private int stepCount;

    /** 本轮是否已经结束 */
    private boolean done;

    /** 本轮最终返回给用户的内容，可能是最终答复，也可能是追问 */
    private String finalAnswer;

    /**
     * 单轮 wall-clock 预算使用 System.nanoTime 计时，不受系统时间校准影响。
     * 未经 AgentRuntime 启动预算时保持“无限”，方便 Workflow 纯单元测试只验证决策逻辑。
     */
    private long turnBudgetStartedAtNanos;
    private long turnBudgetNanos;
    private long configuredTurnBudgetMs;
    private boolean turnBudgetStarted;

    public TurnContext(ChatRequest request, ConversationState state) {
        this.request = request;
        this.state = state;
        this.observations = new ArrayList<>();
        this.traceEvents = new ArrayList<>();
        this.stepCount = 0;
        this.done = false;
        this.turnBudgetNanos = Long.MAX_VALUE;
        this.configuredTurnBudgetMs = Long.MAX_VALUE;
        this.turnBudgetStarted = false;
    }

    public ChatRequest getRequest() {
        return request;
    }

    public ConversationState getState() {
        return state;
    }

    /**
     * 获取当前轮用户消息。
     *
     * 这个方法是一个小封装，避免业务代码到处写 context.getRequest().getMessage()。
     * 后续如果请求结构变化，只需要集中改这里。
     */
    public String getMessage() {
        return request.getMessage();
    }

    public List<Observation> getObservations() {
        return observations;
    }

    public List<TraceEvent> getTraceEvents() {
        return traceEvents;
    }

    /**
     * 添加一条执行轨迹。
     *
     * 【和 Observation 的区别】
     * - Observation 更偏“执行结果”，比如工具返回了什么。
     * - TraceEvent 更偏“过程记录”，比如准备调用哪个工具、参数是什么。
     *
     * 简单说：
     * Observation 给 Skill 做下一步判断；
     * TraceEvent 给开发者、前端调试面板和面试演示看。
     *
     * 【隐私边界】
     * 调用方可以继续把真实业务 Map 传进来，不能为了 Trace 脱敏去修改 Provider 或 LLM 的原始输入。
     * TraceEvent 构造函数会统一创建脱敏、限长、不可变的安全副本，因此扩展 Handler 也不会绕过规则。
     */
    public void addTrace(String eventType, String message, Map<String, Object> data) {
        traceEvents.add(new TraceEvent(eventType, stepCount, message, data));
    }

    /**
     * 添加没有额外 data 的执行轨迹。
     *
     * 有些事件只需要一句说明即可，例如 turn_started。
     */
    public void addTrace(String eventType, String message) {
        addTrace(eventType, message, Map.of());
    }

    /**
     * 添加本轮观察结果。
     *
     * 过渡阶段会同时写入 ConversationState：
     * - 新代码优先从 TurnContext 读本轮观察结果
     * - 旧代码如果还从 ConversationState 读，也不会立刻失效
     *
     * 后续当 Runtime 和 Skill 全部切到 TurnContext 后，
     * 可以再考虑让 ConversationState 不保存本轮观察结果。
     */
    public void addObservation(String type, Object data) {
        Observation observation = new Observation(type, data);
        observations.add(observation);
        state.addObservation(type, data);
    }

    public Observation getLatestObservation() {
        if (observations.isEmpty()) {
            return null;
        }
        return observations.get(observations.size() - 1);
    }

    public Observation getLatestObservationByType(String type) {
        for (int i = observations.size() - 1; i >= 0; i--) {
            if (observations.get(i).getType().equals(type)) {
                return observations.get(i);
            }
        }
        return null;
    }

    public int getStepCount() {
        return stepCount;
    }

    public void increaseStepCount() {
        this.stepCount++;
    }

    public boolean isDone() {
        return done;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    /**
     * 标记本轮已经结束。
     *
     * Runtime 遇到 FinalAnswerAction 或 AskUserAction 时会调用它。
     */
    public void markDone(String finalAnswer) {
        this.done = true;
        this.finalAnswer = finalAnswer;
    }

    /**
     * 由 AgentRuntime 在真正开始一轮执行时启动总时间预算。
     *
     * <p>同一个 TurnContext 如果因测试或错误调用重复进入 Runtime，会从本次 runTurn 重新计时；
     * ConversationState 是跨轮状态，但 wall-clock 预算只属于当前方法调用。</p>
     */
    public void startTurnBudget(long timeoutMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("turn timeout must be positive");
        }
        this.turnBudgetStartedAtNanos = System.nanoTime();
        this.turnBudgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        this.configuredTurnBudgetMs = timeoutMs;
        this.turnBudgetStarted = true;
    }

    public boolean hasTurnBudget() {
        return turnBudgetStarted;
    }

    public long getConfiguredTurnBudgetMs() {
        return configuredTurnBudgetMs;
    }

    /** 返回本轮还剩多少纳秒；未启动预算时返回 Long.MAX_VALUE。 */
    public long remainingTurnBudgetNanos() {
        if (!turnBudgetStarted) {
            return Long.MAX_VALUE;
        }
        long elapsedNanos = System.nanoTime() - turnBudgetStartedAtNanos;
        return Math.max(0, turnBudgetNanos - elapsedNanos);
    }

    /**
     * 向上取整为毫秒，避免还剩不到 1ms 时被下游误认为已经完全没有预算。
     */
    public long remainingTurnBudgetMs() {
        long remainingNanos = remainingTurnBudgetNanos();
        if (remainingNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long wholeMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        return remainingNanos % TimeUnit.MILLISECONDS.toNanos(1) == 0
                ? wholeMillis
                : wholeMillis + 1;
    }

    public boolean isTurnBudgetExceeded() {
        return turnBudgetStarted && remainingTurnBudgetNanos() <= 0;
    }
}
