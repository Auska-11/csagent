package com.smartcs.smart_cs_agent.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HealthCheckController 单元测试。
 *
 * 直接实例化 Controller，不启动完整 Spring 容器，
 * 这样测试速度快，也能验证健康检查响应结构是否稳定。
 */
class HealthCheckControllerTest {

    @Test
    void shouldReturnUpStatus() {
        HealthCheckController controller = new HealthCheckController();

        HealthCheckResponse response = controller.health();

        assertEquals("UP", response.getStatus());
        assertEquals("smart-cs-agent", response.getApplication());
        assertTrue(response.getTimestamp() > 0);
    }
}
