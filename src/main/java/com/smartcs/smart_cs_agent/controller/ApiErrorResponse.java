package com.smartcs.smart_cs_agent.controller;

/**
 * 接口错误响应对象。
 *
 * 【为什么不继续返回 Map？】
 * Map 虽然方便，但字段不固定，调用方不知道一定会有哪些字段。
 * 明确的错误响应对象更适合作为接口契约：
 * - errorCode：程序可读，前端可以根据错误码做不同处理
 * - message：用户或开发者可读
 * - timestamp：方便排查问题发生时间
 */
public class ApiErrorResponse {

    /** 错误码，适合程序判断 */
    private final String errorCode;

    /** 错误说明，适合展示或调试 */
    private final String message;

    /** 错误发生时间，毫秒时间戳 */
    private final long timestamp;

    public ApiErrorResponse(String errorCode, String message) {
        this.errorCode = errorCode;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
