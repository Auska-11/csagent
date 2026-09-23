package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.TraceEvent;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.TurnOutcome;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuManualModeService;
import com.smartcs.smart_cs_agent.security.HttpSecurityConfiguration;
import com.smartcs.smart_cs_agent.security.SecurityErrorWriter;
import com.smartcs.smart_cs_agent.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 聊天接口 MVC 测试。
 *
 * 和 ChatApiControllerTest 不同，这里会启动轻量级 Web 层，
 * 因此可以验证 @Valid、@RequestBody、全局异常处理器是否真的按 HTTP 方式工作。
 */
@WebMvcTest(ChatApiController.class)
@Import({HttpSecurityConfiguration.class, SecurityProperties.class, SecurityErrorWriter.class})
class ChatApiControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CustomerServiceAgent agent;

    @MockBean
    private MonitorService monitorService;

    @MockBean
    private ConversationStore conversationStore;

    @MockBean
    private UserMemoryStore userMemoryStore;

    /** 闲鱼人工模式服务：切片外依赖，Mockito 默认 isBotPaused=false 即自动模式。 */
    @MockBean
    private XianyuManualModeService xianyuManualModeService;

    @Test
    void shouldReturnBadRequestWhenMessageIsBlank() throws Exception {
        mockMvc.perform(post("/api/chat/send")
                        .with(customer("USER-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "   ",
                                  "userId": "USER-001",
                                  "conversationId": "conv-1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Message cannot be empty"));

        // 校验失败时，请求不能进入 Agent 主链路。
        verify(agent, never()).chatWithTrace(any(ChatRequest.class));
        verifyNoInteractions(monitorService);
    }

    @Test
    void shouldReturnChatResponseWhenRequestIsValid() throws Exception {
        when(agent.chatWithTrace(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("conv-1", "USER-001", "ok", List.of()));

        mockMvc.perform(post("/api/chat/send")
                        .with(customer("USER-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "帮我查订单 ORD-001",
                                  "userId": "USER-001",
                                  "conversationId": "conv-1",
                                  "token": "token-zhangsan"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.userId").value("USER-001"))
                .andExpect(jsonPath("$.reply").value("ok"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agent).chatWithTrace(captor.capture());
        verify(monitorService).recordTurn(any(ChatResponse.class), anyLong());
        assertEquals("conv-1", captor.getValue().getConversationId());
        assertEquals("USER-001", captor.getValue().getUserId());
        assertEquals("帮我查订单 ORD-001", captor.getValue().getMessage());
    }

    @Test
    void shouldSerializeOnlySanitizedTraceDataInHttpResponse() throws Exception {
        TraceEvent traceEvent = new TraceEvent(
                "tool_called",
                1,
                "调用测试工具，联系电话 13800138000",
                Map.of(
                        "promptPreview", "TRACE-HTTP-PROMPT",
                        "arguments", Map.of(
                                "order_id", "ORD-001",
                                "apiKey", "http-secret-token"
                        )
                )
        );
        when(agent.chatWithTrace(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("conv-http-trace", "USER-001", "ok", List.of(traceEvent)));

        MvcResult result = mockMvc.perform(post("/api/chat/send")
                        .with(customer("USER-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "测试 Trace HTTP 隐私边界",
                                  "userId": "USER-001",
                                  "conversationId": "conv-http-trace"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trace[0].data.arguments.order_id").value("ORD-001"))
                .andReturn();

        String responseJson = result.getResponse().getContentAsString();
        assertFalse(responseJson.contains("13800138000"));
        assertFalse(responseJson.contains("TRACE-HTTP-PROMPT"));
        assertFalse(responseJson.contains("http-secret-token"));
        assertTrue(responseJson.contains("ORD-001"));
    }

    @Test
    void shouldRecordFailedTurnAndKeepSafeHttpErrorWhenAgentThrows() throws Exception {
        when(agent.chatWithTrace(any(ChatRequest.class)))
                .thenThrow(new IllegalStateException("database password=must-not-reach-http"));

        mockMvc.perform(post("/api/chat/send")
                        .with(customer("USER-001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "触发异常路径",
                                  "userId": "USER-001",
                                  "conversationId": "conv-http-failed"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));

        verify(monitorService).recordFailedTurn(
                eq("conv-http-failed"),
                eq("USER-001"),
                eq(TurnOutcome.TECHNICAL_FAILURE),
                eq("CHAT_TURN_EXECUTION_FAILED"),
                anyLong()
        );
        verify(monitorService, never()).recordTurn(any(ChatResponse.class), anyLong());
    }

    @Test
    void shouldRejectTokenlessClaimBeforeEnteringAgent() throws Exception {
        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "你好",
                                  "userId": "USER-001",
                                  "conversationId": "private-conv"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));

        verify(agent, never()).chatWithTrace(any(ChatRequest.class));
        verifyNoInteractions(monitorService);
    }

    @Test
    void shouldReturnConversationMemoryWhenConversationExists() throws Exception {
        ConversationState state = new ConversationState("我要退货", "USER-001");
        state.setActiveIntent("return_apply");
        state.setPendingSlot("order_id");
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "conv-memory");
        when(conversationStore.get(key)).thenReturn(state);

        mockMvc.perform(get("/api/chat/conversations/conv-memory/memory")
                        .with(customer("USER-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-memory"))
                .andExpect(jsonPath("$.userId").value("USER-001"))
                .andExpect(jsonPath("$.activeIntent").value("return_apply"))
                .andExpect(jsonPath("$.pendingSlot").value("order_id"));
    }

    @Test
    void shouldClearConversationMemory() throws Exception {
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "conv-memory");
        when(conversationStore.clear(key)).thenReturn(true);

        mockMvc.perform(delete("/api/chat/conversations/conv-memory/memory")
                        .with(customer("USER-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("conversation"))
                .andExpect(jsonPath("$.targetId").value("conv-memory"))
                .andExpect(jsonPath("$.cleared").value(true));
    }

    @Test
    void shouldClearUserMemory() throws Exception {
        when(userMemoryStore.clear("USER-001")).thenReturn(true);

        mockMvc.perform(delete("/api/chat/users/USER-001/memory")
                        .with(customer("USER-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("user"))
                .andExpect(jsonPath("$.targetId").value("USER-001"))
                .andExpect(jsonPath("$.cleared").value(true));
    }

    @Test
    void shouldRejectConversationMemoryRequestWithoutBearerToken() throws Exception {
        mockMvc.perform(get("/api/chat/conversations/private-conv/memory"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Authentication is required"));

        verify(conversationStore, never()).get(any(ConversationKey.class));
    }

    @Test
    void shouldReturnNotFoundInsteadOfReadingAnotherUsersConversation() throws Exception {
        ConversationKey ownerKey = ConversationKey.defaultTenant("USER-001", "shared-id");
        ConversationKey attackerKey = ConversationKey.defaultTenant("USER-002", "shared-id");
        when(conversationStore.get(ownerKey))
                .thenReturn(new ConversationState("用户一的私有消息", "USER-001"));

        mockMvc.perform(get("/api/chat/conversations/shared-id/memory")
                        .with(customer("USER-002")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Conversation not found"));

        verify(conversationStore).get(attackerKey);
        verify(conversationStore, never()).get(ownerKey);
    }

    @Test
    void shouldRejectInvalidBearerTokenBeforeController() throws Exception {
        mockMvc.perform(delete("/api/chat/conversations/private-conv/memory")
                        .header("Authorization", "Bearer invalid-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Authentication failed"));

        verify(conversationStore, never()).clear(any(ConversationKey.class));
    }

    @Test
    void shouldRejectReadingAnotherUsersLongTermMemory() throws Exception {
        mockMvc.perform(get("/api/chat/users/USER-001/memory")
                        .with(customer("USER-002")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));

        verify(userMemoryStore, never()).getSnapshot(any());
    }

    private RequestPostProcessor customer(String userId) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.authenticated(
                userId,
                Set.of("CUSTOMER")
        );
        return authentication(new UsernamePasswordAuthenticationToken(
                principal,
                "test-credentials",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        ));
    }
}
