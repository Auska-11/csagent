package com.smartcs.smart_cs_agent.agent.handler.api;

/**
 * ActionHandler 返回给 Runtime 的统一控制结果。
 *
 * <p>Runtime 不需要知道这是 AskUserAction 还是 HandoffAction，只需根据 flow 决定继续循环、
 * 等待用户或结束任务。新增 Action 类型因此不会增加 Runtime 分支。</p>
 */
public final class ActionExecutionResult {

    private final ActionFlow flow;
    private final String answer;

    private ActionExecutionResult(ActionFlow flow, String answer) {
        this.flow = flow;
        this.answer = answer;
        if (flow != ActionFlow.CONTINUE && (answer == null || answer.isBlank())) {
            throw new IllegalArgumentException("Terminal/waiting Action result must contain an answer");
        }
    }

    public static ActionExecutionResult continueLoop() {
        return new ActionExecutionResult(ActionFlow.CONTINUE, null);
    }

    public static ActionExecutionResult waitingForUser(String answer) {
        return new ActionExecutionResult(ActionFlow.WAITING_USER, answer);
    }

    public static ActionExecutionResult completed(String answer) {
        return new ActionExecutionResult(ActionFlow.COMPLETED, answer);
    }

    public static ActionExecutionResult cancelled(String answer) {
        return new ActionExecutionResult(ActionFlow.CANCELLED, answer);
    }

    public static ActionExecutionResult handedOff(String answer) {
        return new ActionExecutionResult(ActionFlow.HANDED_OFF, answer);
    }

    public ActionFlow getFlow() {
        return flow;
    }

    public String getAnswer() {
        return answer;
    }

    public boolean shouldContinue() {
        return flow == ActionFlow.CONTINUE;
    }
}
