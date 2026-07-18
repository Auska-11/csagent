package com.smartcs.smart_cs_agent.observability.health;

import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.PersistenceMode;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.PersistenceHealthMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Persistence readiness 指标的 memory、MyBatis 成功和安全失败测试。 */
class PersistenceHealthIndicatorTest {

    @Test
    void shouldStayUpWithoutTouchingMapperInMemoryMode() {
        PersistenceHealthMapper mapper = mock(PersistenceHealthMapper.class);
        PersistenceHealthIndicator indicator = indicator("memory", mapper);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("memory", health.getDetails().get("mode"));
        verify(mapper, never()).probeCoreTables();
    }

    @Test
    void shouldRequireBothCoreTablesInJdbcMode() {
        PersistenceHealthMapper mapper = mock(PersistenceHealthMapper.class);
        when(mapper.probeCoreTables()).thenReturn(0L);
        PersistenceHealthIndicator indicator = indicator("jdbc", mapper);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("jdbc", health.getDetails().get("mode"));
        verify(mapper).probeCoreTables();
    }

    @Test
    void shouldReturnSafeDownStatusWhenMapperProbeFails() {
        PersistenceHealthMapper mapper = mock(PersistenceHealthMapper.class);
        when(mapper.probeCoreTables()).thenThrow(
                new IllegalStateException("jdbc:mysql://secret-host/root-password")
        );
        PersistenceHealthIndicator indicator = indicator("jdbc", mapper);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("agent persistence is unavailable", health.getDetails().get("reason"));
        assertFalse(health.getDetails().toString().contains("secret-host"));
        assertFalse(health.getDetails().toString().contains("root-password"));
    }

    @SuppressWarnings("unchecked")
    private PersistenceHealthIndicator indicator(String mode,
                                                  PersistenceHealthMapper mapper) {
        ObjectProvider<PersistenceHealthMapper> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mapper);
        return new PersistenceHealthIndicator(
                new PersistenceMode(mode),
                provider,
                new MyBatisMapperExecutor()
        );
    }
}
