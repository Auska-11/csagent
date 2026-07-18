package com.smartcs.smart_cs_agent.persistence;

/** 数据库唯一键冲突的稳定异常类型，让上层不必依赖 MyBatis、Spring 或数据库厂商异常。 */
public class JdbcDuplicateKeyException extends IllegalStateException {

    public JdbcDuplicateKeyException(Throwable cause) {
        super("Database unique key already exists", cause);
    }
}
