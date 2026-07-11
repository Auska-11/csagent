package com.smartcs.smart_cs_agent.agent.handler.defaults;

import com.smartcs.smart_cs_agent.agent.action.AskUserAction;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionExecutionResult;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandler;
import com.smartcs.smart_cs_agent.agent.handler.api.ActionHandlingContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 执行普通槽位追问。 */
@Component
public class AskUserActionHandler implements ActionHandler<AskUserAction> {

    @Override
    public Class<AskUserAction> actionType() {
        return AskUserAction.class;
    }

    @Override
    public ActionExecutionResult handle(AskUserAction action, ActionHandlingContext context) {
        context.getTurnContext().addTrace(
                "ask_user",
                "信息不足，向用户追问",
                Map.of("question", action.getQuestion())
        );
        return ActionExecutionResult.waitingForUser(action.getQuestion());
    }

    @Override
    public Map<String, Object> describe(AskUserAction action) {
        return Map.of("actionType", actionType().getSimpleName(), "question", action.getQuestion());
    }
}
