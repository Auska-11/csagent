package com.smartcs.smart_cs_agent.persistence;

import java.sql.SQLException;

/** JDBC 唯一键冲突的稳定异常类型，让上层不必解析数据库厂商的错误文本。 */
public class JdbcDuplicateKeyException extends IllegalStateException {

    public JdbcDuplicateKeyException(SQLException cause) {
        super("Database unique key already exists", cause);
    }
}
