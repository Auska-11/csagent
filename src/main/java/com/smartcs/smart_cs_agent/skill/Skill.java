package com.smartcs.smart_cs_agent.skill;

import com.smartcs.smart_cs_agent.agent.action.AgentAction;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;

/**
 * Skill 接口
 *
 * 【什么是 Skill？】
 * Skill 负责决策：决定下一步做什么。
 * 它不负责执行，只负责返回一个 Action。
 *
 * 【核心方法】
 * - nextAction()：根据当前状态，决定下一步做什么
 * - getName()：获取 Skill 名称
 * - canHandle()：判断是否能处理这个消息
 *
 * 【与 AgentRuntime 的分工】
 * - Skill：决策（我应该做什么？）→ 返回 Action
 * - AgentRuntime：执行（我来做！）→ 执行 Action
 *
 * 【实现类】
 * - CustomerServiceSkill：客服 Skill（我们实现）
 * - 后续可以加：PreSalesSkill、AfterSalesSkill 等
 */
public interface Skill {

    /**
     * 根据当前状态，决定下一步做什么
     *
     * 【这是 Skill 的核心方法】
     * Skill 会读取 ConversationState，分析用户消息和上下文，
     * 然后返回一个 Action，告诉 AgentRuntime 下一步做什么。
     *
     * @param state 当前会话状态
     * @return 下一步的 Action
     */
    AgentAction nextAction(ConversationState state);

    /**
     * 获取 Skill 名称
     *
     * @return Skill 名称，如 customer_service
     */
    String getName();

    /**
     * 判断是否能处理这个消息
     *
     * 【用于多 Skill 路由】
     * 后续做多 Agent 时，Router 会调用这个方法判断哪个 Skill 处理。
     *
     * @param message 用户消息
     * @return 置信度（0.0 - 1.0）
     */
    float canHandle(String message);
}