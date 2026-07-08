package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.monitor.EvalCaseMergeResult;

/**
 * Eval case 合并接口响应。
 */
public class EvalCaseMergeResponse {

    private final boolean success;
    private final String message;
    private final EvalCaseMergeResult result;

    public EvalCaseMergeResponse(boolean success, String message, EvalCaseMergeResult result) {
        this.success = success;
        this.message = message;
        this.result = result;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public EvalCaseMergeResult getResult() {
        return result;
    }
}
