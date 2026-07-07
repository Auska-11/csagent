package com.smartcs.smart_cs_agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务健康检查接口。
 *
 * 注意：这里不是业务聊天能力，只是告诉外部“服务进程已经启动并能响应 HTTP”。
 * Docker 部署、面试演示、前端调试时，都可以先访问这个接口确认后端是否可用。
 */
@RestController
@RequestMapping("/api/health")
public class HealthCheckController {

    /**
     * 返回最小可用的健康状态。
     *
     * 第一版先不检查数据库、Redis、模型服务等外部依赖，
     * 因为当前项目核心数据还是内存和本地 Markdown 知识库。
     */
    @GetMapping
    public HealthCheckResponse health() {
        return new HealthCheckResponse("UP", "smart-cs-agent", System.currentTimeMillis());
    }
}
