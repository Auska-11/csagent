package com.smartcs.smart_cs_agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旧版兼容健康检查接口。
 *
 * <p>它只告诉外部“服务进程已经启动并能响应 HTTP”，语义等同于 liveness，不检查数据库。
 * 部署探针应优先使用 /actuator/health/liveness 和 /actuator/health/readiness；保留本接口是为了
 * 不破坏旧 Demo、前端调试页面和已有文档。</p>
 */
@RestController
@RequestMapping("/api/health")
public class HealthCheckController {

    /**
     * 返回最小可用的健康状态。
     *
     * 数据库依赖由 Actuator readiness 单独检查，不能把依赖故障混成“JVM 已死亡”。
     */
    @GetMapping
    public HealthCheckResponse health() {
        return new HealthCheckResponse("UP", "smart-cs-agent", System.currentTimeMillis());
    }
}
