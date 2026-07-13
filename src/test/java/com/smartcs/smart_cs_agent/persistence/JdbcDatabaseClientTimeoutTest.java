package com.smartcs.smart_cs_agent.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 JDBC 三层超时真正传递到底层 Driver 和每一条 SQL，而不只停留在配置文件中。 */
class JdbcDatabaseClientTimeoutTest {

    private Driver registeredDriver;

    @AfterEach
    void deregisterTestDriver() throws SQLException {
        if (registeredDriver != null) {
            DriverManager.deregisterDriver(registeredDriver);
        }
    }

    @Test
    void shouldApplyConnectionSocketAndQueryTimeouts() throws SQLException {
        String url = "jdbc:recording-timeout:test";
        Connection connection = mock(Connection.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        PreparedStatement queryStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(updateStatement, queryStatement);
        when(updateStatement.executeUpdate()).thenReturn(1);
        when(queryStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getLong(1)).thenReturn(1L);

        RecordingDriver recordingDriver = new RecordingDriver(url, connection);
        registeredDriver = recordingDriver;
        DriverManager.registerDriver(recordingDriver);

        JdbcDatabaseClient database = new JdbcDatabaseClient(
                "jdbc",
                RecordingDriver.class.getName(),
                url,
                "db-user",
                "db-password",
                1_234,
                2_345,
                7
        );

        assertEquals(1, database.update("UPDATE sample SET value = ?", "changed"));
        assertEquals(1L, database.queryLong("SELECT 1"));

        // update 和 query 各打开一次连接，因此两次都必须带上相同的网络超时和凭据。
        assertEquals(2, recordingDriver.connectionProperties.size());
        for (Properties properties : recordingDriver.connectionProperties) {
            assertEquals("db-user", properties.getProperty("user"));
            assertEquals("db-password", properties.getProperty("password"));
            assertEquals("1234", properties.getProperty("connectTimeout"));
            assertEquals("2345", properties.getProperty("socketTimeout"));
        }

        // 不仅要调用 setQueryTimeout，还必须在真正执行 SQL 之前调用。
        InOrder updateOrder = inOrder(updateStatement);
        updateOrder.verify(updateStatement).setQueryTimeout(7);
        updateOrder.verify(updateStatement).executeUpdate();

        InOrder queryOrder = inOrder(queryStatement);
        queryOrder.verify(queryStatement).setQueryTimeout(7);
        queryOrder.verify(queryStatement).executeQuery();

        // try-with-resources 也是公共客户端的重要契约，否则每次探针都会泄漏连接。
        verify(updateStatement).close();
        verify(queryStatement).close();
        verify(resultSet).close();
        verify(connection, times(2)).close();
    }

    @Test
    void shouldKeepSafeDefaultsInCompatibilityConstructor() throws SQLException {
        String url = "jdbc:recording-timeout:defaults";
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        RecordingDriver recordingDriver = new RecordingDriver(url, connection);
        registeredDriver = recordingDriver;
        DriverManager.registerDriver(recordingDriver);

        // 五参数构造器由旧测试替身使用；它也必须继承安全默认值，不能退化成无限等待。
        JdbcDatabaseClient database = new JdbcDatabaseClient(
                "jdbc",
                RecordingDriver.class.getName(),
                url,
                "db-user",
                "db-password"
        );
        assertEquals(1, database.update("UPDATE sample SET value = 1"));

        Properties properties = recordingDriver.connectionProperties.get(0);
        assertEquals("3000", properties.getProperty("connectTimeout"));
        assertEquals("10000", properties.getProperty("socketTimeout"));
        verify(statement).setQueryTimeout(5);
    }

    @Test
    void shouldReuseOneConnectionAndCommitTransaction() throws SQLException {
        String url = "jdbc:recording-timeout:transaction-commit";
        Connection connection = mock(Connection.class);
        PreparedStatement first = mock(PreparedStatement.class);
        PreparedStatement second = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(first, second);
        when(first.executeUpdate()).thenReturn(1);
        when(second.executeUpdate()).thenReturn(1);

        RecordingDriver recordingDriver = new RecordingDriver(url, connection);
        registeredDriver = recordingDriver;
        DriverManager.registerDriver(recordingDriver);
        JdbcDatabaseClient database = new JdbcDatabaseClient(
                "jdbc", RecordingDriver.class.getName(), url, "db-user", "db-password"
        );

        int changed = database.inTransaction(operations ->
                operations.update("UPDATE first_table SET value = 1")
                        + operations.update("UPDATE second_table SET value = 2")
        );

        assertEquals(2, changed);
        assertEquals(1, recordingDriver.connectionProperties.size(),
                "一个事务只能打开一条 JDBC 连接");
        InOrder transactionOrder = inOrder(connection);
        transactionOrder.verify(connection).setAutoCommit(false);
        transactionOrder.verify(connection).commit();
        transactionOrder.verify(connection).close();
        verify(connection, never()).rollback();
    }

    @Test
    void shouldRollbackTransactionWhenWorkFails() throws SQLException {
        String url = "jdbc:recording-timeout:transaction-rollback";
        Connection connection = mock(Connection.class);
        RecordingDriver recordingDriver = new RecordingDriver(url, connection);
        registeredDriver = recordingDriver;
        DriverManager.registerDriver(recordingDriver);
        JdbcDatabaseClient database = new JdbcDatabaseClient(
                "jdbc", RecordingDriver.class.getName(), url, "db-user", "db-password"
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> database.inTransaction(operations -> {
                    throw new IllegalStateException("simulated transaction failure");
                })
        );

        assertEquals("simulated transaction failure", failure.getMessage());
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void shouldAttemptRollbackWhenCommitFails() throws SQLException {
        String url = "jdbc:recording-timeout:transaction-commit-failure";
        Connection connection = mock(Connection.class);
        org.mockito.Mockito.doThrow(new SQLException("commit failed"))
                .when(connection)
                .commit();
        RecordingDriver recordingDriver = new RecordingDriver(url, connection);
        registeredDriver = recordingDriver;
        DriverManager.registerDriver(recordingDriver);
        JdbcDatabaseClient database = new JdbcDatabaseClient(
                "jdbc", RecordingDriver.class.getName(), url, "db-user", "db-password"
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> database.inTransaction(operations -> "prepared result")
        );

        assertTrue(failure.getMessage().contains("commit"));
        InOrder order = inOrder(connection);
        order.verify(connection).commit();
        order.verify(connection).rollback();
        order.verify(connection).close();
    }

    @Test
    void shouldRejectTimeoutValuesThatDisableTheUpperBound() {
        IllegalArgumentException connectTimeout = assertThrows(
                IllegalArgumentException.class,
                () -> clientWithTimeouts(0, 10_000, 5)
        );
        assertTrue(connectTimeout.getMessage().contains("connect-timeout-ms"));

        IllegalArgumentException socketTimeout = assertThrows(
                IllegalArgumentException.class,
                () -> clientWithTimeouts(3_000, 0, 5)
        );
        assertTrue(socketTimeout.getMessage().contains("socket-timeout-ms"));

        IllegalArgumentException queryTimeout = assertThrows(
                IllegalArgumentException.class,
                () -> clientWithTimeouts(3_000, 10_000, 0)
        );
        assertTrue(queryTimeout.getMessage().contains("query-timeout-seconds"));
    }

    private JdbcDatabaseClient clientWithTimeouts(
            int connectTimeoutMs,
            int socketTimeoutMs,
            int queryTimeoutSeconds) {
        return new JdbcDatabaseClient(
                "memory",
                "",
                "",
                "",
                "",
                connectTimeoutMs,
                socketTimeoutMs,
                queryTimeoutSeconds
        );
    }

    /**
     * 一个只用于测试的 JDBC Driver。它不访问真实网络，只记录 DriverManager 传下来的 Properties，
     * 因此 CI 不需要安装 MySQL，也能发现某个超时参数以后被误删的问题。
     */
    private static final class RecordingDriver implements Driver {

        private final String acceptedUrl;
        private final Connection connection;
        private final List<Properties> connectionProperties = new ArrayList<>();

        private RecordingDriver(String acceptedUrl, Connection connection) {
            this.acceptedUrl = acceptedUrl;
            this.connection = connection;
        }

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) {
                return null;
            }
            Properties copy = new Properties();
            copy.putAll(info);
            connectionProperties.add(copy);
            return connection;
        }

        @Override
        public boolean acceptsURL(String url) {
            return acceptedUrl.equals(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
