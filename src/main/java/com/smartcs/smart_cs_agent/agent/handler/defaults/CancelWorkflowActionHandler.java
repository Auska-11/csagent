package com.smartcs.smart_cs_agent.agent.handler.defaults;

import com.smartcs.smart_cs_agent.agent.action.CancelWorkflowAction;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.core.HandlerTraceSupport;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 执行用户取消 Action，并把结构化 CANCELLED 控制流交给 Runtime。
 *
 * <p>Handler 只负责 Action 到执行结果的转换；清理会话状态和记录终态 Trace 仍由 Runtime 统一完成。</p>
 */
@Component
public final class CancelWorkflowActionHandler implements ActionHandler<CancelWorkflowAction> {

    @Override
    public Class<CancelWorkflowAction> actionType() {
        return CancelWorkflowAction.class;
    }

    @Override
    public ActionExecutionResult handle(CancelWorkflowAction action, ActionHandlingContext context) {
        return ActionExecutionResult.cancelled(action.getAnswer());
    }

    @Override
    public Map<String, Object> describe(CancelWorkflowAction action) {
        return Map.of(
                "actionType", actionType().getSimpleName(),
                "answerPreview", HandlerTraceSupport.preview(action.getAnswer(), 160)
        );
    }
}
