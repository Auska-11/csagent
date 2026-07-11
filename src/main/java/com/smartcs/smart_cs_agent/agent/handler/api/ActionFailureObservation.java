package com.smartcs.smart_cs_agent.agent.handler.api;

/**
 * Handler 框架级失败的结构化 Observation。
 *
 * <p>message 使用固定安全文案，不包含数据库、HTTP 或栈信息；详细异常只进入服务端 Trace/日志。</p>
 */
public final class ActionFailureObservation {

    private final String errorCode;
    private final String actionType;
    private final String handlerId;
    private final String message;

    public ActionFailureObservation(String errorCode,
                                    String actionType,
                                    String handlerId,
                                    String message) {
        this.errorCode = errorCode;
        this.actionType = actionType;
        this.handlerId = handlerId;
        this.message = message;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getActionType() {
        return actionType;
    }

    public String getHandlerId() {
        return handlerId;
    }

    public String getMessage() {
        return message;
    }
}
