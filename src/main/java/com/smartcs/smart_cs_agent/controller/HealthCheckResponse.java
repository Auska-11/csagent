package com.smartcs.smart_cs_agent.controller;

/**
 * 健康检查接口的响应体。
 *
 * 这个 DTO 很简单，但单独建出来有两个好处：
 * 1. 前端或 Docker 探针拿到的是稳定 JSON，不用猜 Map 里有哪些字段。
 * 2. 面试讲部署时，可以说明项目有最基础的服务存活检查能力。
 */
public class HealthCheckResponse {

    private final String status;
    private final String application;
    private final long timestamp;

    public HealthCheckResponse(String status, String application, long timestamp) {
        this.status = status;
        this.application = application;
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public String getApplication() {
        return application;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
