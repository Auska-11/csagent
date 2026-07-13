package com.smartcs.smart_cs_agent.persistence;

import java.util.List;
import java.util.Optional;

/**
 * 一组可以在普通连接或同一事务连接上执行的最小 JDBC 操作。
 *
 * <p>Repository 依赖这个窄接口后，事务回调拿到的是“绑定同一 Connection 的操作对象”，而不是
 * 再次调用会新开连接的 JdbcDatabaseClient。业务代码不需要接触 Connection、commit 或 rollback。</p>
 */
public interface JdbcDatabaseOperations {

    int update(String sql, Object... args);

    <T> Optional<T> queryOne(String sql, JdbcDatabaseClient.RowMapper<T> mapper, Object... args);

    <T> List<T> queryList(String sql, JdbcDatabaseClient.RowMapper<T> mapper, Object... args);

    default long queryLong(String sql, Object... args) {
        return queryOne(sql, resultSet -> resultSet.getLong(1), args).orElse(0L);
    }
}
