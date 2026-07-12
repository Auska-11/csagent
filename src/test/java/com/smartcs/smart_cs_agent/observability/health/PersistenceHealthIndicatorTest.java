package com.smartcs.smart_cs_agent.observability.health;

import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Persistence readiness 指标的 memory、jdbc 成功和安全失败测试。 */
class PersistenceHealthIndicatorTest {

    @Test
    void shouldStayUpWithoutTouchingDatabaseInMemoryMode() {
        JdbcDatabaseClient databaseClient = mock(JdbcDatabaseClient.class);
        when(databaseClient.isEnabled()).thenReturn(false);
        PersistenceHealthIndicator indicator = new PersistenceHealthIndicator(databaseClient);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("memory", health.getDetails().get("mode"));
        verify(databaseClient, never()).queryLong(anyString());
    }

    @Test
    void shouldRequireBothCoreTablesInJdbcMode() {
        JdbcDatabaseClient databaseClient = mock(JdbcDatabaseClient.class);
        when(databaseClient.isEnabled()).thenReturn(true);
        when(databaseClient.queryLong(anyString())).thenReturn(0L);
        PersistenceHealthIndicator indicator = new PersistenceHealthIndicator(databaseClient);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("jdbc", health.getDetails().get("mode"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(databaseClient).queryLong(sql.capture());
        assertTrue(sql.getValue().contains("cs_agent_session"));
        assertTrue(sql.getValue().contains("cs_capability_idempotency"));
    }

    @Test
    void shouldReturnSafeDownStatusWhenJdbcProbeFails() {
        JdbcDatabaseClient databaseClient = mock(JdbcDatabaseClient.class);
        when(databaseClient.isEnabled()).thenReturn(true);
        when(databaseClient.queryLong(anyString())).thenThrow(
                new IllegalStateException("jdbc:mysql://secret-host/root-password")
        );
        PersistenceHealthIndicator indicator = new PersistenceHealthIndicator(databaseClient);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("agent persistence is unavailable", health.getDetails().get("reason"));
        assertFalse(health.getDetails().toString().contains("secret-host"));
        assertFalse(health.getDetails().toString().contains("root-password"));
    }
}
