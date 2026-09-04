package com.smartcs.smart_cs_agent.eval.baseline;

/**
 * 基线 Agent 整轮失败：单循环 Function Calling 没有模板降级机制，
 * 模型调用失败或预算耗尽时整轮以错误结束（对照实验的"降级存活率"指标即测此差异）。
 */
public class BaselineTurnFailedException extends RuntimeException {

    private final String errorCode;

    public BaselineTurnFailedException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
