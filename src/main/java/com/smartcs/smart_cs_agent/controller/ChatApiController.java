package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.CustomerServiceAgent;
import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationBusyException;
import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationMemorySnapshot;
import com.smartcs.smart_cs_agent.agent.context.ConversationState;
import com.smartcs.smart_cs_agent.agent.context.ConversationStore;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuConversationPausedException;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuManualModeService;
import com.smartcs.smart_cs_agent.memory.UserMemorySnapshot;
import com.smartcs.smart_cs_agent.memory.UserMemoryStore;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.TurnOutcome;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import com.smartcs.smart_cs_agent.security.AuthorizationException;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatApiController.class);

    private final CustomerServiceAgent agent;
    private final MonitorService monitorService;
    private final ConversationStore conversationStore;
    private final UserMemoryStore userMemoryStore;
    /** 闲鱼人工模式服务；旧测试构造器传 null 时跳过暂停拦截（不影响商城行为）。 */
    private final XianyuManualModeService manualModeService;

    @Autowired
    public ChatApiController(CustomerServiceAgent agent,
                             MonitorService monitorService,
                             ConversationStore conversationStore,
                             UserMemoryStore userMemoryStore,
                             XianyuManualModeService manualModeService) {
        this.agent = agent;
        this.monitorService = monitorService;
        this.conversationStore = conversationStore;
        this.userMemoryStore = userMemoryStore;
        this.manualModeService = manualModeService;
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

        // 闲鱼人工模式：卖家接管期间机器人不抢答。这里必须在 Agent 之前拦截——
        // 一旦放行，Runtime 会覆盖 Workflow 状态并生成新的自动回复。
        if (manualModeService != null && manualModeService.isBotPaused(chatRequest)) {
            throw new XianyuConversationPausedException(chatRequest.getConversationId());
        }

        // 从进入 Agent 到拿到完整响应的时间才是真正的 Turn 耗时，不能只把工具和 LLM 耗时相加。
        long startedAtNanos = System.nanoTime();
        ChatResponse response;
        try {
            // 返回 ChatResponse，而不是只返回 String。
            // 这样前端除了展示 reply，还可以把 trace 展示到调试控制台。
            response = agent.chatWithTrace(chatRequest);
        } catch (AuthenticationException exception) {
            recordFailedTurn(chatRequest, TurnOutcome.GUARDRAIL_REJECTED, exception.getErrorCode(), startedAtNanos);
            throw exception;
        } catch (AuthorizationException exception) {
            recordFailedTurn(chatRequest, TurnOutcome.GUARDRAIL_REJECTED, "ACCESS_DENIED", startedAtNanos);
            throw exception;
        } catch (RuntimeException exception) {
            recordFailedTurn(chatRequest, TurnOutcome.TECHNICAL_FAILURE, failureCode(exception), startedAtNanos);
            // 记录指标后仍抛出原异常，GlobalExceptionHandler 的 HTTP 状态和安全错误文案保持不变。
            throw exception;
        }

        // 闲鱼会话转人工（含 SELLER_CONFIRM 暂停）即刻持久化人工模式，不依赖侧车再调
        // manual-mode 端点，消除“响应说已暂停、下一条买家消息仍被机器人抢答”的竞态。
        if (response.isHandoffRequested() && manualModeService != null) {
            try {
                manualModeService.markManualAfterHandoff(chatRequest);
            } catch (RuntimeException exception) {
                // 标志写入失败不改变本轮回复；侧车看到 handoffRequested=true 仍可显式暂停。
                LOGGER.warn("闲鱼转人工后写人工模式标志失败: {}",
                        chatRequest.getConversationId(), exception);
            }
        }

        // Monitor 不参与 Agent 决策，只在响应生成后消费 Trace 做统计。
        // 这样即使后续监控指标扩展，也不会影响 Runtime 的主执行逻辑。
        recordSuccessfulTurn(response, startedAtNanos);
        return response;
    }

    private void recordSuccessfulTurn(ChatResponse response, long startedAtNanos) {
        try {
            monitorService.recordTurn(response, elapsedMillis(startedAtNanos));
        } catch (RuntimeException monitorFailure) {
            // 可观测性是旁路能力。即使未来 Monitor 写外部系统失败，也不能把成功聊天改成 HTTP 500。
            logMonitorFailure("record_successful_turn", monitorFailure);
        }
    }

    private void recordFailedTurn(ChatRequest request,
                                  TurnOutcome outcome,
                                  String errorCode,
                                  long startedAtNanos) {
        try {
            monitorService.recordFailedTurn(
                    request.getConversationId(),
                    request.getUserId(),
                    outcome,
                    errorCode,
                    elapsedMillis(startedAtNanos)
            );
        } catch (RuntimeException monitorFailure) {
            // 这里不能抛 monitorFailure，否则会覆盖真正的 Agent/Session 异常并改变既有 HTTP 语义。
            logMonitorFailure("record_failed_turn", monitorFailure);
        }
    }

    private void logMonitorFailure(String operation, RuntimeException failure) {
        // 不记录异常 message 或堆栈，避免未来指标客户端把连接串、请求内容等细节写入应用日志。
        LOGGER.warn("Monitor operation failed: operation={}, exceptionType={}",
                operation,
                failure.getClass().getSimpleName());
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof SessionVersionConflictException) {
            return "SESSION_VERSION_CONFLICT";
        }
        if (exception instanceof ConversationBusyException) {
            return "CONVERSATION_BUSY";
        }
        return "CHAT_TURN_EXECUTION_FAILED";
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
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
        return userMemoryStore.getSnapshot(principal.getUserId());
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
        boolean cleared = userMemoryStore.clear(principal.getUserId());
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
