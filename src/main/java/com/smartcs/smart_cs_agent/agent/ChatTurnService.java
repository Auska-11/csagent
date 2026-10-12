package com.smartcs.smart_cs_agent.agent;

import com.smartcs.smart_cs_agent.agent.context.ChatRequest;
import com.smartcs.smart_cs_agent.agent.context.ChatResponse;
import com.smartcs.smart_cs_agent.agent.context.ConversationBusyException;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuConversationPausedException;
import com.smartcs.smart_cs_agent.channel.xianyu.XianyuManualModeService;
import com.smartcs.smart_cs_agent.monitor.MonitorService;
import com.smartcs.smart_cs_agent.monitor.TurnOutcome;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import com.smartcs.smart_cs_agent.security.AuthorizationException;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

/** One business turn with request policies, handoff state and metrics. */
@Service
public class ChatTurnService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatTurnService.class);
    private final CustomerServiceAgent agent;
    private final MonitorService monitorService;
    private final XianyuManualModeService manualModeService;
    private final List<ChatRequestPolicy> policies;

    @Autowired
    public ChatTurnService(CustomerServiceAgent agent, MonitorService monitorService,
                           XianyuManualModeService manualModeService, List<ChatRequestPolicy> policies) {
        this.agent = agent;
        this.monitorService = monitorService;
        this.manualModeService = manualModeService;
        this.policies = List.copyOf(policies);
    }

    public ChatTurnService(CustomerServiceAgent agent, MonitorService monitorService, XianyuManualModeService manualModeService) {
        this(agent, monitorService, manualModeService, List.of());
    }

    public void validate(ChatRequest request) { policies.forEach(policy -> policy.validate(request)); }

    public ChatResponse execute(ChatRequest chatRequest) {
        validate(chatRequest);
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

}
