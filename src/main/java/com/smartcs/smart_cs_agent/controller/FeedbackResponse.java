package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.monitor.FeedbackRecord;

/**
 * 用户反馈接口响应体。
 *
 * 这个响应不只告诉前端“成功了”，还返回实际记录的反馈对象，
 * 方便前端调试，也方便后续做反馈列表或导出。
 */
public class FeedbackResponse {

    private final boolean success;
    private final String message;
    private final FeedbackRecord feedback;

    public FeedbackResponse(boolean success, String message, FeedbackRecord feedback) {
        this.success = success;
        this.message = message;
        this.feedback = feedback;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public FeedbackRecord getFeedback() {
        return feedback;
    }
}
