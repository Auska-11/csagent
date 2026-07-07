package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.MonitorSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monitor 指标接口。
 *
 * 【第一版接口】
 * GET /api/monitor/summary
 *
 * 它返回的是当前 JVM 内存中的在线指标摘要。
 * 后续如果接 Prometheus、数据库或日志平台，可以保持这个 Controller 不变，只替换 MonitorService 的实现。
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/summary")
    public MonitorSummary getSummary() {
        return monitorService.getSummary();
    }
}
