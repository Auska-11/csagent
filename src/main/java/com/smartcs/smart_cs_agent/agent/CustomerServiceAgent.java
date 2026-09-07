package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.TurnContext;
import com.smartcs.smart_cs_agent.agent.context.WorkflowStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 客服 Agent
 *
 * 【职责】
 * CustomerServiceAgent 是客服场景的 Agent 入口。
 * 它组装 AgentRuntime 和会话存储，提供简单的 chat 接口给 Controller 调用。
 *
 * 【执行流程】
 * 1. 接收用户消息
 * 2. 通过 ConversationStore 读取或创建 ConversationState
 * 3. 调用 AgentRuntime.runTurn()
 * 4. 返回回复
 *
 * 【与 AgentRuntime 的区别】
 * - AgentRuntime：执行引擎，负责循环执行 Action
 * - CustomerServiceAgent：业务入口，负责组装和调用
 *
 * 【后续扩展】
 * Phase 8 做多 Agent 时，可以创建 PreSalesAgent、AfterSalesAgent 等。
 * 每个 Agent 有自己的 Skill 和执行逻辑。
 */
@Component
public class CustomerServiceAgent {

    /** 执行引擎：负责循环执行 Action */
    private final AgentRuntime runtime;

    /** 会话存储：根据 tenantId + userId + conversationId 保存和读取多轮会话状态 */
    private final ConversationStore conversationStore;

    /**
     * 一轮对话的 wall-clock 预算（毫秒）。
     *
     * <p>预算从进入 {@link #chatWithTrace} 就开始计时，排队等会话锁的时间同样消耗预算；
     * 不能等拿到锁之后才由 Runtime 重新计满 30 秒，否则同一会话连续提交慢请求时，
     * 单个请求的总耗时可能远超预算。</p>
     */
    private final long turnTimeoutMs;

    /**
     * 构造函数注入
     *
     * 【Spring 依赖注入】
     * Spring 会自动注入 AgentRuntime 和 ConversationStore。Workflow 由 WorkflowRegistry 自动发现，
     * 业务入口不再手工注册旧 Java Skill，避免新增业务时继续把逻辑堆回 Agent。
     *
     * @param runtime 执行引擎
     * @param conversationStore 会话状态存储
     * @param turnTimeoutMs 单轮对话总预算（含排队等待）
     */
    @Autowired
    public CustomerServiceAgent(AgentRuntime runtime,
                                ConversationStore conversationStore,
                                @Value("${smartcs.agent.turn-timeout-ms:30000}") long turnTimeoutMs) {
        this.runtime = runtime;
        this.conversationStore = conversationStore;
        if (turnTimeoutMs <= 0) {
            throw new IllegalArgumentException("smartcs.agent.turn-timeout-ms must be positive");
        }
        this.turnTimeoutMs = turnTimeoutMs;
    }

    /** 兼容旧测试：不显式传预算时使用与生产配置相同的默认 30 秒。 */
    public CustomerServiceAgent(AgentRuntime runtime, ConversationStore conversationStore) {
        this(runtime, conversationStore, 30_000);
    }

    /**
     * 处理用户消息
     *
     * 【使用场景】
     * Controller 调用这个方法处理用户消息。
     *
     * 【执行流程】
     * 这是兼容旧代码的便捷入口。
     * 新代码推荐直接调用 chat(ChatRequest request)，这样可以明确传入 conversationId。
     *
     * @param message 用户消息
     * @return Agent 回复
     */
    public String chat(String message) {
        // 旧入口没有传 userId 和 conversationId。
        // 第一版先给它一个固定默认会话，保证旧调用方也能复用 ConversationStore。
        ChatRequest request = new ChatRequest("default-anonymous", "anonymous", message);
        return chat(request);
    }

    /**
     * 处理用户消息（带用户 ID）
     *
     * 【使用场景】
     * 需要识别用户身份时使用，比如查询用户自己的订单。
     *
     * @param message 用户消息
     * @param userId 用户 ID
     * @return Agent 回复
     */
    public String chat(String message, String userId) {
        // 注意不要所有用户都使用同一个 "default" 会话 ID。
        // 否则 USER-001 和 USER-002 的多轮任务状态会互相污染。
        ChatRequest request = new ChatRequest("default-" + userId, userId, message);
        return chat(request);
    }

    /**
     * 处理用户消息（新版入口）
     *
     * 【为什么引入 ChatRequest？】
     * ChatRequest 表示“当前这一轮输入”，ConversationState 表示“跨轮状态”。
     * TurnContext 把两者组合起来，表示“当前这一轮 Agent 执行过程”。
     *
     * @param request 当前这一轮聊天请求
     * @return Agent 回复
     */
    public String chat(ChatRequest request) {
        return chatWithTrace(request).getReply();
    }

    /**
     * 处理用户消息，并返回带 Trace 的完整响应。
     *
     * 【为什么新增这个方法？】
     * 旧的 chat(...) 只返回 String，适合最早期 Demo。
     * 现在我们希望前端能看到 Agent 的执行链路，所以新增 ChatResponse：
     * - reply：给用户看的最终回复
     * - trace：给开发者看的执行过程
     *
     * 这样旧调用方仍然可以用 chat(...)，新接口可以用 chatWithTrace(...)。
     *
     * @param request 当前这一轮聊天请求
     * @return 包含回复和 Trace 的完整响应
     */
    public ChatResponse chatWithTrace(ChatRequest request) {
        // 一整轮都放在完整 ConversationKey 对应的串行锁中。
        // 这不仅保护 Map 读取，还保护 Workflow 状态转换和有副作用 Capability 的调用。
        // 预算从这里就开始计时：排队等锁 + 执行共享同一份 wall-clock 预算；
        // 排队超出预算时 ConversationStore 直接抛 ConversationBusyException 快速失败。
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(turnTimeoutMs);
        return conversationStore.executeSerially(request, deadlineNanos, (state, remainingBudgetMs) -> {
            // ChatRequest 负责当前输入，ConversationState 负责跨轮状态，
            // TurnContext 负责本轮观察结果和最终回复。
            TurnContext context = new TurnContext(request, state);
            // 把"排队之后剩余的预算"预置进本轮上下文（ConversationStore 保证 ≥1ms），
            // AgentRuntime 看到预算已启动就不会再用默认值重新计满 30 秒。
            context.startTurnBudget(remainingBudgetMs);

            String reply = runtime.runTurn(context);
            // HANDED_OFF 是本轮终态（下一轮会重新路由并覆盖状态），此刻读取即“本轮是否转人工”，
            // 供闲鱼侧车等渠道适配层暂停该会话的自动回复。
            boolean handoffRequested = state.getWorkflowStatus() == WorkflowStatus.HANDED_OFF;
            return new ChatResponse(
                    request.getConversationId(),
                    request.getUserId(),
                    reply,
                    context.getTraceEvents(),
                    handoffRequested
            );
        });
    }
}
