package com.smartcs.smart_cs_agent.agent.action;

/**
 * Action 基础接口
 *
 * 【什么是 Action？】 * Action 是 Agent 执行过程中可能采取的行动类型。
 * 每次循环，Skill 会决定下一步做什么，返回一个 Action。
 *
 * 【四种 Action 类型】
 * 1. LLMAction - 调用 LLM（意图识别、回复生成）
 * 2. CapabilityAction - 调用工具（查询订单、查询产品）
 * 3. FinalAnswerAction - 最终回复（返回给用户）
 * 4. AskUserAction - 追问用户（缺信息时追问）
 *
 * 【执行流程】
 * Skill.nextAction() -> 返回 Action
 *       ↓
 * AgentRuntime 执行 Action
 *       ↓
 * Skill.nextAction() -> 返回下一个 Action
 *       ↓
 * 循环直到 FinalAnswerAction
 *
 * 【为什么用接口？】
 * - 统一类型，方便扩展
 * - 后续可以加新的 Action 类型（如 TransferAction、ErrorAction）
 * - 用 instanceof 判断类型，执行不同逻辑
 */
public interface AgentAction {
    // 标记接口，不需要定义方法
    // 所有 Action 类型实现这个接口即可
}