package com.smartcs.smart_cs_agent.security;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经过完整 HTTP 安全链验证偏好查询和删除的租户边界。 */
@SpringBootTest
@AutoConfigureMockMvc
class UserMemoryTenantIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMemoryStore store;

    @Test
    void customerMustOnlyReadAndDeleteMemoryWithinTheirAuthenticatedTenant() throws Exception {
        String userId = "shared-http-memory-user";
        store.clear("tenant-a", userId);
        store.clear("tenant-b", userId);
        store.rememberProductPreference("tenant-a", userId, "蓝牙耳机");
        store.rememberProductPreference("tenant-b", userId, "智能手表");
        String path = "/api/chat/users/" + userId + "/memory";

        mockMvc.perform(get(path).with(customer("tenant-a", userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPreferences[0]").value("蓝牙耳机"))
                .andExpect(jsonPath("$.productPreferences.length()").value(1));
        mockMvc.perform(get(path).with(customer("tenant-b", userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPreferences[0]").value("智能手表"));
        mockMvc.perform(delete(path).with(customer("tenant-b", userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleared").value(true));
        mockMvc.perform(get(path).with(customer("tenant-b", userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPreferences").isEmpty());
        mockMvc.perform(get(path).with(customer("tenant-a", userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productPreferences[0]").value("蓝牙耳机"));
    }

    private RequestPostProcessor customer(String tenantId, String userId) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.authenticated(
                tenantId, userId, Set.of("CUSTOMER"));
        return authentication(new UsernamePasswordAuthenticationToken(principal,
                "test-credentials", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }
}
