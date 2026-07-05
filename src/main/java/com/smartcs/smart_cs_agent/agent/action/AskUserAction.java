package com.smartcs.smart_cs_agent.agent.action;

/**
 * 追问用户 Action
 *
 * 【作用】
 * 当 Skill 发现缺少必要信息时，返回这个 Action。
 * AgentRuntime 看到这个 Action 就会停止循环，返回问题给用户。
 *
 * 【使用场景】
 * 1. 查询订单但没提供订单号
 * 2. 申请退换货但没提供原因
 * 3. 需要确认用户身份
 *
 * 【执行流程】
 * 1. Skill 返回 AskUserAction
 * 2. AgentRuntime 停止循环
 * 3. 返回 question 给用户
 * 4. 用户回复后，重新开始循环
 *
 * 【与 FinalAnswerAction 的区别】
 * - FinalAnswerAction：对话结束
 * - AskUserAction：对话未结束，等待用户回复
 *
 * 【例子】
 * 用户：我要退货
 * Skill：缺少订单号 -> AskUserAction("请问您的订单号是多少？")
 * 用户：ORD-001
 * Skill：有订单号了 -> 继续处理
 */
public class AskUserAction implements AgentAction {

    /** 追问的问题：返回给用户的问题 */
    private final String question;

    /**
     * 构造函数
     *
     * @param question 追问的问题
     */
    public AskUserAction(String question) {
        this.question = question;
    }

    /** 获取追问的问题 */
    public String getQuestion() {
        return question;
    }

    @Override
    public String toString() {
        return "AskUserAction{" +
                "question=" + question +
                "}";
    }
}