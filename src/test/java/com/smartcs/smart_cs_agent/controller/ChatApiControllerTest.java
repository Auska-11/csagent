package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationMemorySnapshot;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.memory.UserMemorySnapshot;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 聊天接口 Controller 单元测试。
 *
 * 这里不启动完整 Spring 容器，而是直接测试 Controller 方法。
 * 这样速度更快，也能专注验证“HTTP 请求 DTO -> Agent 请求 DTO”的转换逻辑。
 */
class ChatApiControllerTest {

    @Test
    void shouldRejectEmptyMessageWhenCalledDirectly() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        ChatApiController controller = new ChatApiController(agent, monitorService, new ConversationStore());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> controller.sendMessage(new ChatApiRequest(" ", "USER-001", "conv-1"))
        );

        assertEquals("Message cannot be empty", exception.getMessage());

        // 消息为空时不能进入 Agent，否则会浪费模型调用，也会污染会话状态。
        verify(agent, never()).chatWithTrace(any(ChatRequest.class));
        verify(monitorService, never()).recordTurn(any(ChatResponse.class));
    }

    @Test
    void shouldRejectEmptyRequestBodyWhenCalledDirectly() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        ChatApiController controller = new ChatApiController(agent, monitorService, new ConversationStore());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> controller.sendMessage(null)
        );

        assertEquals("Message cannot be empty", exception.getMessage());
        verify(agent, never()).chatWithTrace(any(ChatRequest.class));
        verify(monitorService, never()).recordTurn(any(ChatResponse.class));
    }

    @Test
    void shouldUseProvidedUserAndConversationId() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        when(agent.chatWithTrace(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            return new ChatResponse(request.getConversationId(), request.getUserId(), "ok", null);
        });

        ChatApiController controller = new ChatApiController(agent, monitorService, new ConversationStore());

        ChatApiRequest apiRequest = new ChatApiRequest("帮我查订单 ORD-001", "USER-001", "conv-1");
        apiRequest.setToken("token-zhangsan");
        ChatResponse response = controller.sendMessage(apiRequest);

        assertInstanceOf(ChatResponse.class, response);
        assertEquals("conv-1", response.getConversationId());
        assertEquals("USER-001", response.getUserId());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agent).chatWithTrace(captor.capture());
        verify(monitorService).recordTurn(response);
        assertEquals("conv-1", captor.getValue().getConversationId());
        assertEquals("USER-001", captor.getValue().getUserId());
        assertEquals(ConversationKey.DEFAULT_TENANT_ID, captor.getValue().getTenantId());
        assertEquals("帮我查订单 ORD-001", captor.getValue().getMessage());
    }

    @Test
    void shouldFillDefaultConversationIdForAuthenticatedUser() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        when(agent.chatWithTrace(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            return new ChatResponse(request.getConversationId(), request.getUserId(), "ok", null);
        });

        ChatApiController controller = new ChatApiController(agent, monitorService, new ConversationStore());

        // 即使请求没有 userId，token 也能恢复真实用户；身份不依赖客户端 claim。
        ChatApiRequest apiRequest = new ChatApiRequest("你好", null, null);
        apiRequest.setToken("token-zhangsan");
        ChatResponse response = controller.sendMessage(apiRequest);

        assertEquals("USER-001", response.getUserId());
        assertEquals("default-USER-001", response.getConversationId());
    }

    @Test
    void shouldRejectClaimedUserIdWithoutToken() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        ChatApiController controller = new ChatApiController(agent, monitorService, new ConversationStore());

        AuthenticationException error = assertThrows(
                AuthenticationException.class,
                () -> controller.sendMessage(
                        new ChatApiRequest("你好", "USER-001", "claimed-private-conv")
                )
        );

        assertEquals("AUTHENTICATION_REQUIRED", error.getErrorCode());
        verify(agent, never()).chatWithTrace(any(ChatRequest.class));
        verify(monitorService, never()).recordTurn(any(ChatResponse.class));
    }

    @Test
    void shouldReturnConversationMemorySnapshot() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        ConversationStore conversationStore = new ConversationStore();
        ConversationState state = new ConversationState("我要退货", "USER-001");
        state.setActiveIntent("return_apply");
        state.setPendingSlot("order_id");
        conversationStore.save(ConversationKey.defaultTenant("USER-001", "conv-memory"), state);

        ChatApiController controller = new ChatApiController(agent, monitorService, conversationStore);

        ConversationMemorySnapshot snapshot = controller.getConversationMemory(
                "conv-memory",
                "USER-001",
                "Bearer token-zhangsan"
        );

        assertEquals("conv-memory", snapshot.getConversationId());
        assertEquals("USER-001", snapshot.getUserId());
        assertEquals("return_apply", snapshot.getActiveIntent());
        assertEquals("order_id", snapshot.getPendingSlot());
    }

    @Test
    void shouldReturnUserMemorySnapshot() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        UserMemoryStore userMemoryStore = new UserMemoryStore();
        userMemoryStore.rememberProductPreference("USER-001", "蓝牙耳机");
        ChatApiController controller = new ChatApiController(
                agent,
                monitorService,
                new ConversationStore(),
                userMemoryStore
        );

        UserMemorySnapshot snapshot = controller.getUserMemory("USER-001", "Bearer token-zhangsan");

        assertEquals("USER-001", snapshot.getUserId());
        assertEquals("蓝牙耳机", snapshot.getProductPreferences().get(0));
    }

    @Test
    void shouldClearConversationMemory() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        ConversationStore conversationStore = new ConversationStore();
        ConversationKey key = ConversationKey.defaultTenant("USER-001", "conv-memory");
        conversationStore.save(key, new ConversationState("我要退货", "USER-001"));
        ChatApiController controller = new ChatApiController(agent, monitorService, conversationStore);

        MemoryClearResponse response = controller.clearConversationMemory(
                "conv-memory",
                "USER-001",
                "Bearer token-zhangsan"
        );

        assertEquals("conversation", response.getScope());
        assertEquals("conv-memory", response.getTargetId());
        assertEquals(true, response.isCleared());
        assertEquals(null, conversationStore.get(key));
    }

    @Test
    void shouldClearUserMemory() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        UserMemoryStore userMemoryStore = new UserMemoryStore();
        userMemoryStore.rememberProductPreference("USER-001", "蓝牙耳机");
        ChatApiController controller = new ChatApiController(
                agent,
                monitorService,
                new ConversationStore(),
                userMemoryStore
        );

        MemoryClearResponse response = controller.clearUserMemory("USER-001", "Bearer token-zhangsan");

        assertEquals("user", response.getScope());
        assertEquals("USER-001", response.getTargetId());
        assertEquals(true, response.isCleared());
        assertEquals(0, userMemoryStore.getSnapshot("USER-001").getProductPreferences().size());
    }

    @Test
    void shouldRequireAuthenticationBeforeReadingConversationMemory() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        ConversationStore conversationStore = new ConversationStore();
        ConversationKey ownerKey = ConversationKey.defaultTenant("USER-001", "private-conv");
        conversationStore.save(ownerKey, new ConversationState("私有消息", "USER-001"));
        ChatApiController controller = new ChatApiController(agent, monitorService, conversationStore);

        AuthenticationException error = assertThrows(
                AuthenticationException.class,
                () -> controller.getConversationMemory("private-conv", "USER-001", null)
        );

        assertEquals("AUTHENTICATION_REQUIRED", error.getErrorCode());
        assertEquals("私有消息", conversationStore.get(ownerKey).getMessages().get(0).get("content"));
    }

    @Test
    void shouldNotReadOrClearAnotherUsersSameConversationId() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        ConversationStore conversationStore = new ConversationStore();
        ConversationKey ownerKey = ConversationKey.defaultTenant("USER-001", "shared-id");
        conversationStore.save(ownerKey, new ConversationState("用户一的私有消息", "USER-001"));
        ChatApiController controller = new ChatApiController(agent, monitorService, conversationStore);

        assertThrows(
                ApiNotFoundException.class,
                () -> controller.getConversationMemory(
                        "shared-id",
                        "USER-002",
                        "Bearer token-lisi"
                )
        );

        MemoryClearResponse clearResponse = controller.clearConversationMemory(
                "shared-id",
                "USER-002",
                "Bearer token-lisi"
        );
        assertEquals(false, clearResponse.isCleared());
        assertEquals(
                "用户一的私有消息",
                conversationStore.get(ownerKey).getMessages().get(0).get("content")
        );
    }
}
