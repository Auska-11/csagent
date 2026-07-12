package com.smartcs.smart_cs_agent.agent.action;

/**
 * 用户主动取消当前 Workflow 时返回的通用 Action。
 *
 * <p>它和 FinalAnswerAction 都会给用户一段最终回复，但两者的结束原因不同：普通最终回复是
 * COMPLETED，取消回复是 CANCELLED。把这个差异做成结构化 Action 后，Runtime、Trace 和 Monitor
 * 都不需要从“已取消”之类的自然语言文案中猜测结果。</p>
 */
public final class CancelWorkflowAction implements AgentAction {

    /** 给用户看的取消确认，不包含监控或流程控制字段。 */
    private final String answer;

    public CancelWorkflowAction(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("Cancelled workflow answer cannot be empty");
        }
        this.answer = answer;
    }

    public String getAnswer() {
        return answer;
    }

    @Override
    public String toString() {
        return "CancelWorkflowAction{" +
                "answerLength=" + answer.length() +
                '}';
    }
}
