package com.smartcs.smart_cs_agent.observability.health;

import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Agent 核心持久化依赖的 readiness 指标。
 *
 * <p>memory 模式不依赖数据库，直接返回 UP。jdbc 模式执行只读探针，确认数据库连接、会话表和
 * Capability 幂等表仍然可读。这里不抛异常，也不把 JDBC URL、账号或 SQL 异常原文放进健康响应。</p>
 */
@Component("persistenceHealthIndicator")
public class PersistenceHealthIndicator implements HealthIndicator {

    private static final String CORE_TABLES_PROBE_SQL = """
            SELECT
                (SELECT 1 FROM cs_agent_session LIMIT 1) AS session_ready,
                (SELECT 1 FROM cs_capability_idempotency LIMIT 1) AS idempotency_ready
            """;

    private final JdbcDatabaseClient databaseClient;

    public PersistenceHealthIndicator(JdbcDatabaseClient databaseClient) {
        this.databaseClient = Objects.requireNonNull(databaseClient, "databaseClient cannot be null");
    }

    @Override
    public Health health() {
        if (!databaseClient.isEnabled()) {
            return Health.up()
                    .withDetail("mode", "memory")
                    .build();
        }

        try {
            // 子查询即使没有业务行也会成功；queryLong 返回 0 仍表示连接和两张表都可读。
            // 合成一条 SQL 是为了让一次 readiness 轮询只创建一个 JDBC 连接。
            databaseClient.queryLong(CORE_TABLES_PROBE_SQL);
            return Health.up()
                    .withDetail("mode", "jdbc")
                    .build();
        } catch (RuntimeException persistenceFailure) {
            // 健康端点可能被负载均衡器直接访问，不能在这里暴露数据库异常或连接信息。
            return Health.down()
                    .withDetail("mode", "jdbc")
                    .withDetail("reason", "agent persistence is unavailable")
                    .build();
        }
    }
}
