package com.smartcs.smart_cs_agent.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** MyBatis/Spring DAO 异常必须继续收敛成上层已依赖的稳定异常。 */
class MyBatisMapperExecutorTest {

    private final MyBatisMapperExecutor executor = new MyBatisMapperExecutor();

    @Test
    void shouldTranslateDuplicateKeyToStableConflictType() {
        RuntimeException error = assertThrows(
                JdbcDuplicateKeyException.class,
                () -> executor.execute(() -> {
                    throw new DuplicateKeyException("vendor duplicate details");
                })
        );
        assertInstanceOf(DuplicateKeyException.class, error.getCause());
    }

    @Test
    void shouldTranslateQueryTimeoutToIllegalStateException() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(() -> {
                    throw new QueryTimeoutException("query timed out");
                })
        );
        assertInstanceOf(QueryTimeoutException.class, error.getCause());
    }

    @Test
    void shouldTranslateDatabaseUnavailableToIllegalStateException() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(() -> {
                    throw new DataAccessResourceFailureException("database unavailable");
                })
        );
        assertInstanceOf(DataAccessResourceFailureException.class, error.getCause());
    }
}
