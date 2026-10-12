package com.smartcs.smart_cs_agent.eval.baseline;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 普通应用启动时，即使已认证的客户或管理员也不能访问实验 Agent。 */
@SpringBootTest
@AutoConfigureMockMvc
class BaselineDisabledIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void defaultStartupMustNotLoadAnyBaselineComponentOrExposeItsEndpoint() throws Exception {
        for (Class<?> type : List.of(BaselineChatController.class, BaselineFunctionCallingAgent.class,
                BaselineConversationStore.class, BaselineSystemPrompt.class, BaselineToolCatalog.class)) {
            assertTrue(context.getBeansOfType(type).isEmpty(), type.getSimpleName());
        }
        for (String role : List.of("CUSTOMER", "ADMIN")) {
            AuthenticatedPrincipal principal = AuthenticatedPrincipal.authenticated("USER-001", Set.of(role));
            mockMvc.perform(post("/api/chat/baseline/send")
                            .with(authentication(new UsernamePasswordAuthenticationToken(principal,
                                    "test-credentials", List.of(new SimpleGrantedAuthority("ROLE_" + role)))))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"直接提交退货\"}"))
                    .andExpect(status().isNotFound());
        }
    }
}
