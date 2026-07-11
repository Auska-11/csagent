package com.smartcs.smart_cs_agent.agent.handler.defaults;

import com.smartcs.smart_cs_agent.agent.action.ConfirmAction;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 执行副作用确认请求，但绝不在本 Handler 中调用目标 Capability。 */
@Component
public class ConfirmActionHandler implements ActionHandler<ConfirmAction> {

    @Override
    public Class<ConfirmAction> actionType() {
        return ConfirmAction.class;
    }

    @Override
    public ActionExecutionResult handle(ConfirmAction action, ActionHandlingContext context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", action.getOperation());
        data.put("summary", action.getSummary());
        data.put("question", action.getQuestion());
        context.getTurnContext().addTrace(
                "confirmation_requested",
                "有副作用操作等待用户明确确认",
                data
        );
        return ActionExecutionResult.waitingForUser(action.getQuestion());
    }

    @Override
    public Map<String, Object> describe(ConfirmAction action) {
        Map<String, Object> data = ActionHandler.super.describe(action);
        data.put("operation", action.getOperation());
        data.put("summary", action.getSummary());
        return data;
    }
}
