package com.smartcs.smart_cs_agent.agent.handler.defaults;

import com.smartcs.smart_cs_agent.agent.action.FinalAnswerAction;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import com.smartcs.smart_cs_agent.agent.handler.core.HandlerTraceSupport;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 执行最终回复：记录 Trace，并告诉 Runtime 结束当前 Workflow。 */
@Component
public class FinalAnswerActionHandler implements ActionHandler<FinalAnswerAction> {

    @Override
    public Class<FinalAnswerAction> actionType() {
        return FinalAnswerAction.class;
    }

    @Override
    public ActionExecutionResult handle(FinalAnswerAction action, ActionHandlingContext context) {
        return ActionExecutionResult.completed(action.getAnswer());
    }

    @Override
    public Map<String, Object> describe(FinalAnswerAction action) {
        return Map.of(
                "actionType", actionType().getSimpleName(),
                "answerPreview", HandlerTraceSupport.preview(action.getAnswer(), 160)
        );
    }
}
