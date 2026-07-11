package com.smartcs.smart_cs_agent.agent.handler.core;

/** 未注册 Action Handler 的稳定框架异常。 */
public class ActionHandlerNotFoundException extends IllegalStateException {

    public static final String ERROR_CODE = "ACTION_HANDLER_NOT_FOUND";

    private final String actionType;

    public ActionHandlerNotFoundException(String actionType) {
        super(ERROR_CODE + ": No ActionHandler registered for " + actionType);
        this.actionType = actionType;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public String getActionType() {
        return actionType;
    }
}
