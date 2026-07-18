package com.smartcs.smart_cs_agent.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 0 在驱动/MyBatis 中可能表示永不超时，因此所有可靠性配置必须严格为正。 */
class MyBatisPersistenceConfigurationTest {

    private final MyBatisPersistenceConfiguration configuration =
            new MyBatisPersistenceConfiguration();

    @Test
    void shouldRejectNonPositiveConnectionTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.dataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:invalid",
                        "sa",
                        "",
                        0,
                        1,
                        1,
                        1
                )
        );
    }

    @Test
    void shouldRejectNonPositiveSocketTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.dataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:invalid",
                        "sa",
                        "",
                        1,
                        0,
                        1,
                        1
                )
        );
    }

    @Test
    void shouldRejectNonPositiveStatementTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.statementTimeoutCustomizer(0)
        );
    }

    @Test
    void shouldRejectNonPositivePoolSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> configuration.dataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:invalid",
                        "sa",
                        "",
                        1,
                        1,
                        1,
                        0
                )
        );
    }
}
