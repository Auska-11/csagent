package com.smartcs.smart_cs_agent.channel.xianyu;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 默认关闭（smartcs.xianyu.enabled=false）时，会话端点必须表现为不存在：
 * 不区分“没带令牌/令牌错误”，避免未部署环境暴露任何接入面。
 */
@SpringBootTest(properties = "smartcs.xianyu.enabled=false")
@AutoConfigureMockMvc
class XianyuSidecarDisabledIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sessionEndpointShouldDisappearWhenDisabled() throws Exception {
        mockMvc.perform(post("/api/xianyu/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer whatever-token")
                        .content("""
                                {"buyerId":"221000111","itemId":"987654321"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }
}
