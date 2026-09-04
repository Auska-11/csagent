package com.smartcs.smart_cs_agent.eval.baseline;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.controller.ApiErrorResponse;
import com.smartcs.smart_cs_agent.controller.ChatApiRequest;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.TurnOutcome;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 基线 Function Calling Agent 的对照实验入口。
 *
 * <p>刻意复用与 {@code POST /api/chat/send} 完全相同的请求/响应形状、JWT 认证
 * （/api/chat/** 规则：CUSTOMER 或 ADMIN）和身份校验语义，使 ScenarioRunner 能以
 * 完全相同的方式驱动两个被测方（公平原则 5：相同消息序列、相同身份、相同种子数据）。</p>
 *
 * <p>路径放在 /api/chat 下而不是 /api/eval 下：/api/eval/** 要求 ADMIN，
 * 会把只有 CUSTOMER 角色的 persona（USER-002）挡在外面，破坏对照实验的身份公平性。</p>
 */
@RestController
@RequestMapping("/api/chat/baseline")
public class BaselineChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaselineChatController.class);

    private final BaselineFunctionCallingAgent agent;
    private final MonitorService monitorService;

    @Autowired
    public BaselineChatController(BaselineFunctionCallingAgent agent, MonitorService monitorService) {
        this.agent = agent;
        this.monitorService = monitorService;
    }

    @PostMapping("/send")
    public Object sendMessage(
            @Valid @RequestBody(required = false) ChatApiRequest request,
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {
        if (request == null) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }

        requireAuthenticated(principal);
        requireMatchingClaim(request.getUserId(), principal);
        String actualUserId = principal.getUserId();
        String conversationId = normalizeConversationId(request.getConversationId(), actualUserId);
        ChatRequest chatRequest = new ChatRequest(
                principal.getTenantId(),
                conversationId,
                actualUserId,
                message,
                principal
        );

        long startedAtNanos = System.nanoTime();
        try {
            ChatResponse response = agent.chat(chatRequest);
            recordSuccessfulTurn(response, startedAtNanos);
            return response;
        } catch (BaselineTurnFailedException failure) {
            // 基线没有模板降级：LLM 失败或预算耗尽时整轮失败（对照实验的降级存活率指标）。
            // 错误码用真实原因而不是笼统的 INTERNAL_ERROR，报告里能区分超时/提供方故障/步数耗尽。
            try {
                monitorService.recordFailedTurn(
                        conversationId,
                        actualUserId,
                        TurnOutcome.TECHNICAL_FAILURE,
                        failure.getErrorCode(),
                        elapsedMillis(startedAtNanos)
                );
            } catch (RuntimeException monitorFailure) {
                LOGGER.warn("Monitor operation failed: operation=baseline_record_failed_turn, exceptionType={}",
                        monitorFailure.getClass().getSimpleName());
            }
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiErrorResponse(failure.getErrorCode(), "Baseline agent turn failed"));
        }
    }

    private void recordSuccessfulTurn(ChatResponse response, long startedAtNanos) {
        try {
            monitorService.recordTurn(response, elapsedMillis(startedAtNanos));
        } catch (RuntimeException monitorFailure) {
            LOGGER.warn("Monitor operation failed: operation=baseline_record_success_turn, exceptionType={}",
                    monitorFailure.getClass().getSimpleName());
        }
    }

    private void requireAuthenticated(AuthenticatedPrincipal principal) {
        if (principal == null || !principal.isAuthenticated()) {
            throw AuthenticationException.required();
        }
    }

    /** 与 ChatApiController 相同的 claim 语义：显式传了 userId 就必须与 JWT 一致。 */
    private void requireMatchingClaim(String claimedUserId, AuthenticatedPrincipal principal) {
        if (claimedUserId != null
                && !claimedUserId.isBlank()
                && !"anonymous".equals(claimedUserId)
                && !claimedUserId.equals(principal.getUserId())) {
            throw AuthenticationException.failed();
        }
    }

    private String normalizeConversationId(String conversationId, String userId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationId;
        }
        return "baseline-default-" + userId;
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}
