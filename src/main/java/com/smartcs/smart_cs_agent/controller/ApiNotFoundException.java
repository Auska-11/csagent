package com.smartcs.smart_cs_agent.controller;

/** 用于把明确的 API 资源缺失转换成统一 404 响应。 */
public final class ApiNotFoundException extends RuntimeException {

    public ApiNotFoundException(String message) {
        super(message);
    }
}
