package com.smartcs.smart_cs_agent.observability.health;

import com.smartcs.smart_cs_agent.persistence.JdbcDatabaseClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 持久化依赖掉线时 readiness 与 liveness 必须分离。 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadinessFailureEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcDatabaseClient databaseClient;

    @BeforeEach
    void setUp() {
        when(databaseClient.isEnabled()).thenReturn(true);
        when(databaseClient.queryLong(anyString())).thenThrow(
                new IllegalStateException("jdbc:mysql://secret-host/root-password")
        );
    }

    @Test
    void shouldReturn503ForReadinessWhileLivenessRemainsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.persistence.status").value("DOWN"))
                .andExpect(content().string(not(containsString("secret-host"))))
                .andExpect(content().string(not(containsString("root-password"))));

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
