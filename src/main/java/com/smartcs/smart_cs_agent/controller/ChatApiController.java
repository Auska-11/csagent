package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationMemorySnapshot;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.memory.UserMemorySnapshot;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.security.DemoAuthenticationService;
import com.smartcs.smart_cs_agent.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private final CustomerServiceAgent agent;
    private final MonitorService monitorService;
    private final ConversationStore conversationStore;
    private final UserMemoryStore userMemoryStore;
    private final DemoAuthenticationService authenticationService;

    @Autowired
    public ChatApiController(CustomerServiceAgent agent,
                             MonitorService monitorService,
                             ConversationStore conversationStore,
                             UserMemoryStore userMemoryStore,
                             DemoAuthenticationService authenticationService) {
        this.agent = agent;
        this.monitorService = monitorService;
        this.conversationStore = conversationStore;
        this.userMemoryStore = userMemoryStore;
        this.authenticationService = authenticationService;
    }

    /**
     * 测试用构造函数。
     *
     * 旧的 Controller 单元测试只关心聊天请求转换，
     * 不需要显式创建 UserMemoryStore，所以这里提供一个默认内存实现。
     */
    public ChatApiController(CustomerServiceAgent agent,
                             MonitorService monitorService,
                             ConversationStore conversationStore) {
        this(
                agent,
                monitorService,
                conversationStore,
                new UserMemoryStore(),
                new DemoAuthenticationService(new UserRepository())
        );
    }

    /** 兼容需要传入自定义 UserMemoryStore 的旧单元测试。 */
    public ChatApiController(CustomerServiceAgent agent,
                             MonitorService monitorService,
                             ConversationStore conversationStore,
                             UserMemoryStore userMemoryStore) {
        this(
                agent,
                monitorService,
                conversationStore,
                userMemoryStore,
                new DemoAuthenticationService(new UserRepository())
        );
    }

    @PostMapping("/send")
    public ChatResponse sendMessage(@Valid @RequestBody(required = false) ChatApiRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        String message = request.getMessage();
        String userId = request.getUserId();
        String conversationId = request.getConversationId();

        // 真实 HTTP 请求会被 @Valid 拦住；这里是给单元测试或内部直接调用留一道兜底防线。
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        String claimedUserId = userId != null ? userId : "anonymous";
        AuthenticatedPrincipal principal = authenticationService.authenticate(claimedUserId, request.getToken());
        String actualUserId = principal.isAuthenticated() ? principal.getUserId() : claimedUserId;
        String actualConversationId = normalizeConversationId(conversationId, actualUserId);
        ChatRequest chatRequest = new ChatRequest(actualConversationId, actualUserId, message, principal);

        // 返回 ChatResponse，而不是只返回 String。
        // 这样前端除了展示 reply，还可以把 trace 展示到调试控制台。
        ChatResponse response = agent.chatWithTrace(chatRequest);
        // Monitor 不参与 Agent 决策，只在响应生成后消费 Trace 做统计。
        // 这样即使后续监控指标扩展，也不会影响 Runtime 的主执行逻辑。
        monitorService.recordTurn(response);
        return response;
    }

    /**
     * 规范化 conversationId。
     *
     * 如果前端没有传 conversationId，就生成一个默认会话 ID。
     * 第一版先用 userId 生成稳定默认值，方便旧前端不用改也能继续使用。
     *
     * 注意：
     * - 这样同一个用户默认只有一个会话。
     * - 后续前端支持“新建会话”后，应该由前端传入真正的 conversationId。
     */
    private String normalizeConversationId(String conversationId, String userId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationId;
        }
        return "default-" + userId;
    }

    @GetMapping("/users")
    public List<Map<String, String>> getUsers() {
        return List.of(
            Map.of("userId", "USER-001", "name", "Zhang San", "token", "token-zhangsan"),
            Map.of("userId", "USER-002", "name", "Li Si", "token", "token-lisi")
        );
    }

    @GetMapping("/conversations/{conversationId}/memory")
    public ConversationMemorySnapshot getConversationMemory(@PathVariable String conversationId) {
        ConversationState state = conversationStore.get(conversationId);
        if (state == null) {
            throw new IllegalArgumentException("Conversation not found");
        }
        return state.toMemorySnapshot(conversationId);
    }

    /**
     * 清空指定会话记忆。
     *
     * 这相当于“重置当前对话任务状态”，适合用户想重新开始一次客服流程时使用。
     */
    @DeleteMapping("/conversations/{conversationId}/memory")
    public MemoryClearResponse clearConversationMemory(@PathVariable String conversationId) {
        boolean cleared = conversationStore.clear(conversationId);
        String message = cleared ? "Conversation memory cleared" : "Conversation memory not found";
        return new MemoryClearResponse("conversation", conversationId, cleared, message, System.currentTimeMillis());
    }

    /**
     * 查看用户长期记忆。
     *
     * 这个接口和会话记忆接口不同：
     * - 会话记忆按 conversationId 查，服务当前任务。
     * - 用户长期记忆按 userId 查，服务跨会话个性化。
     */
    @GetMapping("/users/{userId}/memory")
    public UserMemorySnapshot getUserMemory(@PathVariable String userId) {
        return userMemoryStore.getSnapshot(userId);
    }

    /**
     * 清空用户长期记忆。
     *
     * 这个接口体现“用户可控”：Agent 可以记住偏好，也应该允许用户清除偏好。
     */
    @DeleteMapping("/users/{userId}/memory")
    public MemoryClearResponse clearUserMemory(@PathVariable String userId) {
        boolean cleared = userMemoryStore.clear(userId);
        String message = cleared ? "User memory cleared" : "User memory not found";
        return new MemoryClearResponse("user", userId, cleared, message, System.currentTimeMillis());
    }
}
