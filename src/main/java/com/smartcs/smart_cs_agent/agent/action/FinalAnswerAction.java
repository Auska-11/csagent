package com.smartcs.smart_cs_agent.agent.action;

/**
 * 最终回复 Action
 *
 * 【作用】
 * 当 Skill 认为回复已经准备好时，返回这个 Action。
 * AgentRuntime 看到这个 Action 就会停止循环，返回回复给用户。
 *
 * 【使用场景】
 * 1. LLM 生成了回复
 * 2. 工具返回了结果，Skill 组织好了回复
 * 3. 不需要调用工具，直接回复（如闲聊）
 *
 * 【执行流程】
 * 1. Skill 返回 FinalAnswerAction
 * 2. AgentRuntime 停止循环
 * 3. 返回 answer 给用户
 *
 * 【什么时候返回？】
 * - LLM 生成了回复（response_generation 完成）
 * - 工具返回了结果，Skill 组织好了回复
 * - 不需要调用工具，直接回复（如闲聊）
 */
public class FinalAnswerAction implements AgentAction {

    /** 回复内容：返回给用户的内容 */
    private final String answer;

    /**
     * 构造函数
     *
     * @param answer 回复内容
     */
    public FinalAnswerAction(String answer) {
        this.answer = answer;
    }

    /** 获取回复内容 */
    public String getAnswer() {
        return answer;
    }

    @Override
    public String toString() {
        return "FinalAnswerAction{" +
                "answer=" + answer.substring(0, Math.min(50, answer.length())) + "..." +
                "}";
    }
}