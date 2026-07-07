package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 聊天接口 MVC 测试。
 *
 * 和 ChatApiControllerTest 不同，这里会启动轻量级 Web 层，
 * 因此可以验证 @Valid、@RequestBody、全局异常处理器是否真的按 HTTP 方式工作。
 */
@WebMvcTest(ChatApiController.class)
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

    @Test
    void shouldReturnBadRequestWhenMessageIsBlank() throws Exception {
        mockMvc.perform(post("/api/chat/send")
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
        verify(monitorService, never()).recordTurn(any(ChatResponse.class));
    }

    @Test
    void shouldReturnChatResponseWhenRequestIsValid() throws Exception {
        when(agent.chatWithTrace(any(ChatRequest.class)))
                .thenReturn(new ChatResponse("conv-1", "USER-001", "ok", List.of()));

        mockMvc.perform(post("/api/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "帮我查订单 ORD-001",
                                  "userId": "USER-001",
                                  "conversationId": "conv-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-1"))
                .andExpect(jsonPath("$.userId").value("USER-001"))
                .andExpect(jsonPath("$.reply").value("ok"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agent).chatWithTrace(captor.capture());
        verify(monitorService).recordTurn(any(ChatResponse.class));
        assertEquals("conv-1", captor.getValue().getConversationId());
        assertEquals("USER-001", captor.getValue().getUserId());
        assertEquals("帮我查订单 ORD-001", captor.getValue().getMessage());
    }

    @Test
    void shouldReturnConversationMemoryWhenConversationExists() throws Exception {
        ConversationState state = new ConversationState("我要退货", "USER-001");
        state.setActiveIntent("return_apply");
        state.setPendingSlot("order_id");
        when(conversationStore.get("conv-memory")).thenReturn(state);

        mockMvc.perform(get("/api/chat/conversations/conv-memory/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("conv-memory"))
                .andExpect(jsonPath("$.userId").value("USER-001"))
                .andExpect(jsonPath("$.activeIntent").value("return_apply"))
                .andExpect(jsonPath("$.pendingSlot").value("order_id"));
    }
}
