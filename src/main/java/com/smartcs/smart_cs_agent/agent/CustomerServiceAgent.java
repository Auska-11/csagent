package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.skill.CustomerServiceSkill;
import com.smartcs.smart_cs_agent.skill.SkillManager;
import org.springframework.stereotype.Component;

/**
 * 客服 Agent
 *
 * 【职责】
 * CustomerServiceAgent 是客服场景的 Agent 入口。
 * 它组装 AgentRuntime 和 Skill，提供简单的 chat 接口给 Controller 调用。
 *
 * 【执行流程】
 * 1. 接收用户消息
 * 2. 创建 ConversationState
 * 3. 注册 CustomerServiceSkill
 * 4. 调用 AgentRuntime.runTurn()
 * 5. 返回回复
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

    /** Skill 管理器：管理所有注册的 Skill */
    private final SkillManager skillManager;

    /**
     * 构造函数注入
     *
     * 【Spring 依赖注入】
     * Spring 会自动注入 AgentRuntime 和 SkillManager。
     * CustomerServiceSkill 也由 Spring 创建并注入。
     *
     * 【注册 Skill】
     * 在构造函数中注册 CustomerServiceSkill，
     * 这样 SkillManager 就知道有这个 Skill 可用。
     *
     * @param runtime 执行引擎
     * @param skillManager Skill 管理器
     * @param customerServiceSkill 客服 Skill（由 Spring 注入）
     */
    public CustomerServiceAgent(AgentRuntime runtime, SkillManager skillManager, CustomerServiceSkill customerServiceSkill) {
        this.runtime = runtime;
        this.skillManager = skillManager;

        // 注册 CustomerServiceSkill
        skillManager.registerSkill(customerServiceSkill);
    }

    /**
     * 处理用户消息
     *
     * 【使用场景】
     * Controller 调用这个方法处理用户消息。
     *
     * 【执行流程】
     * 1. 创建 ConversationState（包含用户消息）
     * 2. 调用 AgentRuntime.runTurn() 执行对话
     * 3. 返回 Agent 回复
     *
     * @param message 用户消息
     * @return Agent 回复
     */
    public String chat(String message) {
        // 创建会话状态
        ConversationState state = new ConversationState(message);

        // 调用执行引擎
        return runtime.runTurn(state);
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
        // 创建会话状态（包含用户消息和用户 ID）
        ConversationState state = new ConversationState(message, userId);

        // 调用执行引擎
        return runtime.runTurn(state);
    }
}