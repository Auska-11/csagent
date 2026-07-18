package com.smartcs.smart_cs_agent.persistence;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 将 MyBatis/Spring DAO 异常收敛成迁移前对上层暴露的稳定异常。
 *
 * <p>唯一键仍使用 {@link JdbcDuplicateKeyException} 表示可预期的并发竞争；查询超时、连接失败
 * 和其他数据库故障继续以 IllegalStateException 穿过现有业务边界。</p>
 */
@Component
public class MyBatisMapperExecutor {

    public void run(Runnable operation) {
        execute(() -> {
            operation.run();
            return null;
        });
    }

    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation cannot be null");
        try {
            return operation.get();
        } catch (DuplicateKeyException duplicateKey) {
            throw new JdbcDuplicateKeyException(duplicateKey);
        } catch (DataAccessException databaseFailure) {
            throw new IllegalStateException("Database operation failed", databaseFailure);
        }
    }
}
