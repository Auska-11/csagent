package com.smartcs.smart_cs_agent.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 人工转接配置。
 *
 * 【为什么单独做配置类？】
 * 因为人工转接有两种运行方式：
 * 1. memory：默认模式，只生成本地模拟工单，适合单独启动 Agent 项目和跑测试。
 * 2. after-sales-http：联调模式，调用售后工单平台创建真实工单。
 *
 * 这样可以避免“为了联调必须启动另一个项目”，本项目的本地开发和 CI 会更稳定。
 */
@Component
@ConfigurationProperties(prefix = "smartcs.handoff")
public class HandoffProperties {

    /** memory 或 after-sales-http */
    private String mode = "memory";

    /** 售后工单平台相关配置 */
    private AfterSales afterSales = new AfterSales();

    public boolean isAfterSalesHttpMode() {
        return "after-sales-http".equalsIgnoreCase(mode);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public AfterSales getAfterSales() {
        return afterSales;
    }

    public void setAfterSales(AfterSales afterSales) {
        this.afterSales = afterSales;
    }

    public static class AfterSales {

        /** 售后工单平台地址，默认对应桌面 demo 项目的 8081 端口 */
        private String baseUrl = "http://localhost:8081";

        /** Agent 作为系统用户调用工单平台 */
        private String agentUserId = "agent-service";

        /** demo 后端允许 AGENT 角色创建工单 */
        private String agentUserRole = "AGENT";

        /**
         * 按幂等键查询结果的 Adapter 模式。
         *
         * <p>当前售后平台没有公开查询接口，所以只能使用 unavailable。保留独立配置是为了未来新增
         * HTTP Adapter 时只切换装配，不修改 Admin 业务流程。</p>
         */
        private String resultQueryMode = "unavailable";

        /** 建立 TCP 连接最多等待多久；不能设为 0，否则可能无限等待。 */
        private int connectTimeoutMs = 3000;

        /** 工单平台建立连接后，最多等待响应多久。 */
        private int readTimeoutMs = 10000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getAgentUserId() {
            return agentUserId;
        }

        public void setAgentUserId(String agentUserId) {
            this.agentUserId = agentUserId;
        }

        public String getAgentUserRole() {
            return agentUserRole;
        }

        public void setAgentUserRole(String agentUserRole) {
            this.agentUserRole = agentUserRole;
        }

        public String getResultQueryMode() {
            return resultQueryMode;
        }

        public void setResultQueryMode(String resultQueryMode) {
            if (resultQueryMode == null || resultQueryMode.isBlank()) {
                throw new IllegalArgumentException(
                        "smartcs.handoff.after-sales.result-query-mode cannot be blank"
                );
            }
            this.resultQueryMode = resultQueryMode.trim().toLowerCase(Locale.ROOT);
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = BoundedRestClientFactory.requirePositive(
                    "smartcs.handoff.after-sales.connect-timeout-ms",
                    connectTimeoutMs
            );
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = BoundedRestClientFactory.requirePositive(
                    "smartcs.handoff.after-sales.read-timeout-ms",
                    readTimeoutMs
            );
        }
    }
}
