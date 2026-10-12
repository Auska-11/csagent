package com.smartcs.smart_cs_agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.ChatTurnService;
import com.smartcs.smart_cs_agent.messaging.InMemoryMessageInboxStore;
import com.smartcs.smart_cs_agent.messaging.MessageReceiptExecutor;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationMemorySnapshot;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuManualModeService;
import com.smartcs.smart_cs_agent.memory.UserMemorySnapshot;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private final ChatTurnService turns;
    private final MessageReceiptExecutor receipts;
    private final ConversationStore conversationStore;
    private final UserMemoryStore userMemoryStore;

    @Autowired
    public ChatApiController(ChatTurnService turns, MessageReceiptExecutor receipts,
                             ConversationStore conversationStore, UserMemoryStore userMemoryStore) {
        this.turns = turns;
        this.receipts = receipts;
        this.conversationStore = conversationStore;
        this.userMemoryStore = userMemoryStore;
    }

    /** Explicit wiring for controller unit tests; production receives the configured inbox store. */
    public ChatApiController(CustomerServiceAgent agent, MonitorService monitorService,
                             ConversationStore conversationStore, UserMemoryStore userMemoryStore,
                             XianyuManualModeService manualModeService) {
        this(new ChatTurnService(agent, monitorService, manualModeService),
                new MessageReceiptExecutor(new InMemoryMessageInboxStore(), new ObjectMapper()),
                conversationStore, userMemoryStore);
    }

    /** 生产装配兼容：未显式注入人工模式服务时仍可构建（等价于关闭暂停拦截）。 */
    public ChatApiController(CustomerServiceAgent agent,
                             MonitorService monitorService,
                             ConversationStore conversationStore,
                             UserMemoryStore userMemoryStore) {
        this(agent, monitorService, conversationStore, userMemoryStore, null);
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
                new UserMemoryStore()
        );
    }

    @PostMapping("/send")
    public ChatResponse sendMessage(
            @Valid @RequestBody(required = false) ChatApiRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        if (request == null) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        String message = request.getMessage();
        String conversationId = request.getConversationId();

        // 真实 HTTP 请求会被 @Valid 拦住；这里是给单元测试或内部直接调用留一道兜底防线。
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        requireAuthenticated(principal);
        requireMatchingClaim(request.getUserId(), principal);
        // userId 和 tenantId 只相信 Spring Security 验证 JWT 后生成的 Principal。
        String actualUserId = principal.getUserId();
        String actualConversationId = normalizeConversationId(conversationId, actualUserId);
        ChatRequest chatRequest = new ChatRequest(
                principal.getTenantId(),
                actualConversationId,
                actualUserId,
                message,
                principal
        );

        turns.validate(chatRequest);
        return receipts.execute(
                MessageReceiptExecutor.scope("chat", principal.getTenantId(), actualUserId, actualConversationId),
                request.getMessageId(), message, ChatResponse.class,
                () -> turns.execute(chatRequest)
        );
    }

    /**
     * 规范化 conversationId。
     *
     * 如果前端没有传 conversationId，就生成一个默认会话 ID。
     * 第一版先用 userId 生成稳定默认值，方便旧前端不用改也能继续使用。
     *
     * 注意：
     * - 这样同一个用户默认只有一个会话。
     * - HTTP Chat 已要求认证，因此这里的 userId 一定来自可信 Principal。
     * - 后续前端支持“新建会话”后，应该由前端传入真正的 conversationId。
     */
    private String normalizeConversationId(String conversationId, String userId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationId;
        }
        return "default-" + userId;
    }

    @GetMapping("/conversations/{conversationId}/memory")
    public ConversationMemorySnapshot getConversationMemory(
            @PathVariable String conversationId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireAuthenticated(principal);
        ConversationKey key = new ConversationKey(
                principal.getTenantId(),
                principal.getUserId(),
                conversationId
        );
        ConversationState state = conversationStore.get(key);
        if (state == null) {
            // 对“确实不存在”和“存在但属于别人”返回完全相同的结果，避免泄露会话归属。
            throw new ApiNotFoundException("Conversation not found");
        }
        return state.toMemorySnapshot(conversationId);
    }

    /**
     * 清空指定会话记忆。
     *
     * 这相当于“重置当前对话任务状态”，适合用户想重新开始一次客服流程时使用。
     */
    @DeleteMapping("/conversations/{conversationId}/memory")
    public MemoryClearResponse clearConversationMemory(
            @PathVariable String conversationId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireAuthenticated(principal);
        ConversationKey key = new ConversationKey(
                principal.getTenantId(),
                principal.getUserId(),
                conversationId
        );
        boolean cleared = conversationStore.clear(key);
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
    public UserMemorySnapshot getUserMemory(
            @PathVariable String userId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireSelfOrHide(userId, principal);
        return userMemoryStore.getSnapshot(principal.getTenantId(), principal.getUserId());
    }

    /**
     * 清空用户长期记忆。
     *
     * 这个接口体现“用户可控”：Agent 可以记住偏好，也应该允许用户清除偏好。
     */
    @DeleteMapping("/users/{userId}/memory")
    public MemoryClearResponse clearUserMemory(
            @PathVariable String userId,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        requireSelfOrHide(userId, principal);
        boolean cleared = userMemoryStore.clear(principal.getTenantId(), principal.getUserId());
        String message = cleared ? "User memory cleared" : "User memory not found";
        return new MemoryClearResponse("user", userId, cleared, message, System.currentTimeMillis());
    }

    private void requireAuthenticated(AuthenticatedPrincipal principal) {
        if (principal == null || !principal.isAuthenticated()) {
            throw AuthenticationException.required();
        }
    }

    private void requireMatchingClaim(String claimedUserId, AuthenticatedPrincipal principal) {
        if (claimedUserId != null
                && !claimedUserId.isBlank()
                && !"anonymous".equals(claimedUserId)
                && !claimedUserId.equals(principal.getUserId())) {
            throw AuthenticationException.failed();
        }
    }

    private void requireSelfOrHide(String requestedUserId, AuthenticatedPrincipal principal) {
        requireAuthenticated(principal);
        if (!principal.getUserId().equals(requestedUserId)) {
            // 对其他用户的长期记忆返回 404，不暴露目标用户是否存在。
            throw new ApiNotFoundException("User memory not found");
        }
    }
}
