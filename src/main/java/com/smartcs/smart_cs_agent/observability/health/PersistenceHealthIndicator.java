package com.smartcs.smart_cs_agent.observability.health;

import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.PersistenceMode;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.PersistenceHealthMapper;
import org.springframework.beans.factory.ObjectProvider;
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

    private final PersistenceMode persistenceMode;
    private final PersistenceHealthMapper mapper;
    private final MyBatisMapperExecutor mapperExecutor;

    public PersistenceHealthIndicator(PersistenceMode persistenceMode,
                                      ObjectProvider<PersistenceHealthMapper> mapperProvider,
                                      MyBatisMapperExecutor mapperExecutor) {
        this.persistenceMode = Objects.requireNonNull(
                persistenceMode,
                "persistenceMode cannot be null"
        );
        this.mapper = mapperProvider.getIfAvailable();
        this.mapperExecutor = Objects.requireNonNull(
                mapperExecutor,
                "mapperExecutor cannot be null"
        );
    }

    @Override
    public Health health() {
        if (!persistenceMode.isJdbc()) {
            return Health.up()
                    .withDetail("mode", "memory")
                    .build();
        }

        try {
            if (mapper == null) {
                throw new IllegalStateException("MyBatis persistence mapper is unavailable");
            }
            // 子查询即使没有业务行也会成功；合成一条 SQL 使一次 readiness 只借用一个池连接。
            mapperExecutor.execute(mapper::probeCoreTables);
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
