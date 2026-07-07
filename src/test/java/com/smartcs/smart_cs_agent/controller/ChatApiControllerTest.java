package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationMemorySnapshot;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.memory.UserMemorySnapshot;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
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

        ChatResponse response = controller.sendMessage(
                new ChatApiRequest("帮我查订单 ORD-001", "USER-001", "conv-1")
        );

        assertInstanceOf(ChatResponse.class, response);
        assertEquals("conv-1", response.getConversationId());
        assertEquals("USER-001", response.getUserId());

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(agent).chatWithTrace(captor.capture());
        verify(monitorService).recordTurn(response);
        assertEquals("conv-1", captor.getValue().getConversationId());
        assertEquals("USER-001", captor.getValue().getUserId());
        assertEquals("帮我查订单 ORD-001", captor.getValue().getMessage());
    }

    @Test
    void shouldFillDefaultUserAndConversationId() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        when(agent.chatWithTrace(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            return new ChatResponse(request.getConversationId(), request.getUserId(), "ok", null);
        });

        ChatApiController controller = new ChatApiController(agent, monitorService, new ConversationStore());

        ChatResponse response = controller.sendMessage(new ChatApiRequest("你好", null, null));

        assertEquals("anonymous", response.getUserId());
        assertEquals("default-anonymous", response.getConversationId());
    }

    @Test
    void shouldReturnConversationMemorySnapshot() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        ConversationStore conversationStore = new ConversationStore();
        ConversationState state = new ConversationState("我要退货", "USER-001");
        state.setActiveIntent("return_apply");
        state.setPendingSlot("order_id");
        conversationStore.save("conv-memory", state);

        ChatApiController controller = new ChatApiController(agent, monitorService, conversationStore);

        ConversationMemorySnapshot snapshot = controller.getConversationMemory("conv-memory");

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

        UserMemorySnapshot snapshot = controller.getUserMemory("USER-001");

        assertEquals("USER-001", snapshot.getUserId());
        assertEquals("蓝牙耳机", snapshot.getProductPreferences().get(0));
    }

    @Test
    void shouldClearConversationMemory() {
        CustomerServiceAgent agent = mock(CustomerServiceAgent.class);
        MonitorService monitorService = mock(MonitorService.class);
        ConversationStore conversationStore = new ConversationStore();
        conversationStore.save("conv-memory", new ConversationState("我要退货", "USER-001"));
        ChatApiController controller = new ChatApiController(agent, monitorService, conversationStore);

        MemoryClearResponse response = controller.clearConversationMemory("conv-memory");

        assertEquals("conversation", response.getScope());
        assertEquals("conv-memory", response.getTargetId());
        assertEquals(true, response.isCleared());
        assertEquals(null, conversationStore.get("conv-memory"));
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

        MemoryClearResponse response = controller.clearUserMemory("USER-001");

        assertEquals("user", response.getScope());
        assertEquals("USER-001", response.getTargetId());
        assertEquals(true, response.isCleared());
        assertEquals(0, userMemoryStore.getSnapshot("USER-001").getProductPreferences().size());
    }
}
