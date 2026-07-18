package com.smartcs.smart_cs_agent.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 即使测试 classpath 存在 H2，默认 memory 模式也不能创建 DataSource 或 Mapper。 */
@SpringBootTest
class PersistenceModeConfigurationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void memoryModeShouldNotCreateAnyDataSource() {
        assertTrue(applicationContext.getBeansOfType(DataSource.class).isEmpty());
    }
}
