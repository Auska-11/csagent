package com.smartcs.smart_cs_agent.persistence;

import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Properties;

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
public class JdbcDatabaseClient implements JdbcDatabaseOperations {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 10_000;
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 5;

    private final String mode;
    private final String driverClassName;
    private final String url;
    private final String username;
    private final String password;
    private final int connectTimeoutMs;
    private final int socketTimeoutMs;
    private final int queryTimeoutSeconds;

    /**
     * 兼容测试替身和手工构造场景的简化构造器。
     *
     * <p>Spring 不会选择这个构造器；应用运行时会使用下面带 {@link Autowired} 的完整构造器。
     * 保留它是为了不让学习型测试必须知道所有生产配置细节。</p>
     */
    public JdbcDatabaseClient(
            String mode,
            String driverClassName,
            String url,
            String username,
            String password) {
        this(
                mode,
                driverClassName,
                url,
                username,
                password,
                DEFAULT_CONNECT_TIMEOUT_MS,
                DEFAULT_SOCKET_TIMEOUT_MS,
                DEFAULT_QUERY_TIMEOUT_SECONDS
        );
    }

    /**
     * Spring 运行时使用的完整构造器。
     *
     * <p>三种超时各自保护一段不同的阻塞过程：</p>
     * <ul>
     *     <li>connectTimeout：建立 TCP/JDBC 连接最多等待多久；</li>
     *     <li>socketTimeout：连接建立后，等待数据库网络响应最多多久；</li>
     *     <li>queryTimeout：客户端等待一条 SQL 多久，超时后由驱动尝试取消。</li>
     * </ul>
     */
    @Autowired
    public JdbcDatabaseClient(
            @Value("${smartcs.persistence.mode:memory}") String mode,
            @Value("${smartcs.persistence.jdbc.driver-class-name:com.mysql.cj.jdbc.Driver}") String driverClassName,
            @Value("${smartcs.persistence.jdbc.url:}") String url,
            @Value("${smartcs.persistence.jdbc.username:}") String username,
            @Value("${smartcs.persistence.jdbc.password:}") String password,
            @Value("${smartcs.persistence.jdbc.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${smartcs.persistence.jdbc.socket-timeout-ms:10000}") int socketTimeoutMs,
            @Value("${smartcs.persistence.jdbc.query-timeout-seconds:5}") int queryTimeoutSeconds) {
        this.mode = mode == null ? "memory" : mode.trim().toLowerCase(Locale.ROOT);
        this.driverClassName = driverClassName;
        this.url = url;
        this.username = username;
        this.password = password;
        this.connectTimeoutMs = requirePositive(
                "smartcs.persistence.jdbc.connect-timeout-ms",
                connectTimeoutMs
        );
        this.socketTimeoutMs = requirePositive(
                "smartcs.persistence.jdbc.socket-timeout-ms",
                socketTimeoutMs
        );
        this.queryTimeoutSeconds = requirePositive(
                "smartcs.persistence.jdbc.query-timeout-seconds",
                queryTimeoutSeconds
        );

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

    @Override
    public int update(String sql, Object... args) {
        try (Connection connection = openConnection()) {
            return executeUpdate(connection, sql, args);
        } catch (SQLException e) {
            throw sqlFailure("Failed to open JDBC connection for update", e);
        }
    }

    @Override
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = queryList(sql, mapper, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... args) {
        try (Connection connection = openConnection()) {
            return executeQuery(connection, sql, mapper, args);
        } catch (SQLException e) {
            throw sqlFailure("Failed to open JDBC connection for query", e);
        }
    }

    @Override
    public long queryLong(String sql, Object... args) {
        return queryOne(sql, resultSet -> resultSet.getLong(1), args).orElse(0L);
    }

    /**
     * 在同一 JDBC Connection 中执行一组操作，并统一 commit/rollback。
     *
     * <p>回调里必须只使用传入的 operations；若重新调用外层 databaseClient，会打开另一条连接，
     * 那些 SQL 就不属于当前事务。这个约束会在人工确认工单的 Repository 代码旁再次注释。</p>
     */
    public <T> T inTransaction(TransactionWork<T> work) {
        if (work == null) {
            throw new IllegalArgumentException("transaction work cannot be null");
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            JdbcDatabaseOperations operations = new ConnectionBoundOperations(connection);
            T result;
            try {
                result = work.execute(operations);
            } catch (RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            }
            try {
                connection.commit();
            } catch (SQLException commitFailure) {
                // commit 失败时结果可能未知，但仍必须显式尝试 rollback，不能把行为交给不同驱动的
                // Connection.close()。rollback 若也失败会作为 suppressed 保留。
                rollback(connection, commitFailure);
                throw sqlFailure("Failed to commit JDBC transaction", commitFailure);
            }
            return result;
        } catch (SQLException e) {
            throw sqlFailure("Failed to execute JDBC transaction", e);
        }
    }

    private int executeUpdate(Connection connection, String sql, Object... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            // 超时要在 executeUpdate/executeQuery 之前设置，才能覆盖真正的 SQL 执行阶段。
            statement.setQueryTimeout(queryTimeoutSeconds);
            bindArgs(statement, args);
            return statement.executeUpdate();
        } catch (SQLException e) {
            if (isDuplicateKey(e)) {
                throw new JdbcDuplicateKeyException(e);
            }
            throw new IllegalStateException("Failed to execute SQL update: " + sql, e);
        }
    }

    private <T> List<T> executeQuery(Connection connection,
                                     String sql,
                                     RowMapper<T> mapper,
                                     Object... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
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

    private void rollback(Connection connection, Throwable originalFailure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            // 保留真正触发回滚的业务/数据库异常，并把回滚失败作为 suppressed 证据交给内部日志。
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    private IllegalStateException sqlFailure(String message, SQLException cause) {
        return new IllegalStateException(message, cause);
    }

    private Connection openConnection() throws SQLException {
        if (!isEnabled()) {
            throw new IllegalStateException("JDBC persistence is not enabled.");
        }

        /*
         * MySQL Connector/J 通过 Properties 接收毫秒级连接和 Socket 超时。
         * 不把超时直接拼进 URL，可以让密码、地址和运行参数保持各自独立，也更容易测试。
         */
        Properties properties = new Properties();
        properties.setProperty("user", valueOrEmpty(username));
        properties.setProperty("password", valueOrEmpty(password));
        properties.setProperty("connectTimeout", Integer.toString(connectTimeoutMs));
        properties.setProperty("socketTimeout", Integer.toString(socketTimeoutMs));
        return DriverManager.getConnection(url, properties);
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

    private int requirePositive(String propertyName, int value) {
        if (value <= 0) {
            // 0 在 JDBC 中通常表示“永不超时”，会重新引入健康探针无限阻塞风险。
            throw new IllegalArgumentException(propertyName + " must be greater than 0");
        }
        return value;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * MySQL/MariaDB 的重复键错误码是 1062，H2 的 SQLState 是 23505。
     *
     * <p>这里只识别“唯一键已存在”，不把所有 23xxx 约束错误都当作并发竞争，否则 NOT NULL、
     * 外键等真实数据问题会被错误吞掉。</p>
     */
    private boolean isDuplicateKey(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            if (current.getErrorCode() == 1062 || "23505".equals(current.getSQLState())) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(JdbcDatabaseOperations operations);
    }

    /** 所有方法都复用同一个事务 Connection，不负责 commit 或 close。 */
    private final class ConnectionBoundOperations implements JdbcDatabaseOperations {

        private final Connection connection;

        private ConnectionBoundOperations(Connection connection) {
            this.connection = connection;
        }

        @Override
        public int update(String sql, Object... args) {
            return executeUpdate(connection, sql, args);
        }

        @Override
        public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            List<T> rows = queryList(sql, mapper, args);
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        }

        @Override
        public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... args) {
            return executeQuery(connection, sql, mapper, args);
        }
    }
}
