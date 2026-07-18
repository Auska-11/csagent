package com.smartcs.smart_cs_agent.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * jdbc 模式专用的 Hikari/MyBatis/Spring 事务基础设施。
 *
 * <p>memory 模式不会注册 DataSource、SqlSessionFactory 或 Mapper，因此不会加载驱动、建立连接，
 * 也不会改变现有 Demo/单元测试的无数据库启动行为。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
@MapperScan("com.smartcs.smart_cs_agent.persistence.mybatis.mapper")
public class MyBatisPersistenceConfiguration {

    @Bean
    public ConfigurationCustomizer statementTimeoutCustomizer(
            @Value("${smartcs.persistence.jdbc.query-timeout-seconds:5}")
            int queryTimeoutSeconds) {
        requirePositive(
                "smartcs.persistence.jdbc.query-timeout-seconds",
                queryTimeoutSeconds
        );
        return configuration ->
                configuration.setDefaultStatementTimeout(queryTimeoutSeconds);
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource dataSource(
            @Value("${smartcs.persistence.jdbc.driver-class-name:com.mysql.cj.jdbc.Driver}")
            String driverClassName,
            @Value("${smartcs.persistence.jdbc.url:}") String url,
            @Value("${smartcs.persistence.jdbc.username:}") String username,
            @Value("${smartcs.persistence.jdbc.password:}") String password,
            @Value("${smartcs.persistence.jdbc.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${smartcs.persistence.jdbc.socket-timeout-ms:10000}") int socketTimeoutMs,
            @Value("${smartcs.persistence.jdbc.query-timeout-seconds:5}") int queryTimeoutSeconds,
            @Value("${smartcs.persistence.jdbc.pool-max-size:10}") int poolMaxSize) {
        requirePositive("smartcs.persistence.jdbc.connect-timeout-ms", connectTimeoutMs);
        requirePositive("smartcs.persistence.jdbc.socket-timeout-ms", socketTimeoutMs);
        requirePositive("smartcs.persistence.jdbc.query-timeout-seconds", queryTimeoutSeconds);
        requirePositive("smartcs.persistence.jdbc.pool-max-size", poolMaxSize);

        HikariConfig config = new HikariConfig();
        config.setPoolName("smartcs-persistence");
        config.setDriverClassName(driverClassName);
        config.setJdbcUrl(url);
        config.setUsername(username == null ? "" : username);
        config.setPassword(password == null ? "" : password);
        config.setMaximumPoolSize(poolMaxSize);
        config.setMinimumIdle(0);
        // Hikari 的 acquisition timeout 下限为 250ms；驱动自己的 connectTimeout 仍保留精确值。
        config.setConnectionTimeout(Math.max(250L, connectTimeoutMs));
        config.setValidationTimeout(Math.max(250L, Math.min(5_000L, connectTimeoutMs)));
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", connectTimeoutMs);
        config.addDataSourceProperty("socketTimeout", socketTimeoutMs);
        return new HikariDataSource(config);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public TransactionTemplate transactionTemplate(
            PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    private void requirePositive(String propertyName, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(propertyName + " must be greater than 0");
        }
    }
}
