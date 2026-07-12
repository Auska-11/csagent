package com.smartcs.smart_cs_agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 生产关闭 Demo JWT 签发器后，公开地址表现为不存在，不能泄露本地演示身份。 */
@SpringBootTest(properties = "smartcs.security.demo-issuer-enabled=false")
@AutoConfigureMockMvc
class DemoIssuerDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void demoTokenEndpointShouldDisappearWhenIssuerIsDisabled() throws Exception {
        mockMvc.perform(post("/api/auth/demo/tokens"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }
}
