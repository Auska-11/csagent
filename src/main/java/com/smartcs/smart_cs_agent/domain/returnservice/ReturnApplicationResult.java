package com.smartcs.smart_cs_agent.domain.returnservice;

import com.smartcs.smart_cs_agent.tools.ReturnToolData;

/** 领域服务提交退货申请后的明确业务结果。 */
public final class ReturnApplicationResult {

    private final boolean submitted;
    private final String code;
    private final String message;
    private final ReturnToolData data;

    private ReturnApplicationResult(boolean submitted,
                                    String code,
                                    String message,
                                    ReturnToolData data) {
        this.submitted = submitted;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static ReturnApplicationResult submitted(String message, ReturnToolData data) {
        return new ReturnApplicationResult(true, null, message, data);
    }

    public static ReturnApplicationResult rejected(String code, String message, ReturnToolData data) {
        return new ReturnApplicationResult(false, code, message, data);
    }

    public boolean isSubmitted() {
        return submitted;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public ReturnToolData getData() {
        return data;
    }
}
