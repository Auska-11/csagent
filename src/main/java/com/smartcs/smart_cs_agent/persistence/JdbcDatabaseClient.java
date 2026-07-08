package com.smartcs.smart_cs_agent.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 项目第一版 JDBC 数据库客户端。
 *
 * 【为什么不用一上来就用 JPA？】
 * 当前项目的实体类和 Repository 已经比较清晰，直接上 JPA 会引入 Entity 注解、事务、
 * 懒加载等额外概念。第一版先用 JDBC，可以让你更直观看到：
 * - SQL 表怎么建；
 * - Repository 如何从内存 Map 切换到数据库；
 * - 为什么要保留内存模式作为测试和演示兜底。
 *
 * 【默认不开启数据库】
 * 只有 smartcs.persistence.mode=jdbc 时才会真正加载驱动并连接数据库。
 * 默认 memory 模式不会碰数据库，所以单元测试和 CI 仍然稳定。
 */
@Component
public class JdbcDatabaseClient {

    private final String mode;
    private final String driverClassName;
    private final String url;
    private final String username;
    private final String password;

    public JdbcDatabaseClient(
            @Value("${smartcs.persistence.mode:memory}") String mode,
            @Value("${smartcs.persistence.jdbc.driver-class-name:com.mysql.cj.jdbc.Driver}") String driverClassName,
            @Value("${smartcs.persistence.jdbc.url:}") String url,
            @Value("${smartcs.persistence.jdbc.username:}") String username,
            @Value("${smartcs.persistence.jdbc.password:}") String password) {
        this.mode = mode == null ? "memory" : mode.trim().toLowerCase(Locale.ROOT);
        this.driverClassName = driverClassName;
        this.url = url;
        this.username = username;
        this.password = password;

        if (isEnabled()) {
            loadDriver();
        }
    }

    /**
     * 是否启用 JDBC 持久化。
     *
     * Repository 会根据这个开关决定走数据库还是走内存 Map。
     */
    public boolean isEnabled() {
        return "jdbc".equals(mode);
    }

    public int update(String sql, Object... args) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindArgs(statement, args);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to execute SQL update: " + sql, e);
        }
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = queryList(sql, mapper, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... args) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindArgs(statement, args);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to execute SQL query: " + sql, e);
        }
    }

    public long queryLong(String sql, Object... args) {
        return queryOne(sql, resultSet -> resultSet.getLong(1), args).orElse(0L);
    }

    private Connection openConnection() throws SQLException {
        if (!isEnabled()) {
            throw new IllegalStateException("JDBC persistence is not enabled.");
        }
        return DriverManager.getConnection(url, username, password);
    }

    private void bindArgs(PreparedStatement statement, Object... args) throws SQLException {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    private void loadDriver() {
        try {
            Class.forName(driverClassName);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("JDBC driver not found: " + driverClassName, e);
        }
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }
}
