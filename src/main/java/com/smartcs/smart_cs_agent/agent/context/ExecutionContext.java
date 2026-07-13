package com.smartcs.smart_cs_agent.agent.context;

import java.time.Instant;
import java.util.Objects;

/**
 * 执行 Action 或 Capability 时使用的安全上下文。
 *
 * <p>TurnContext 主要描述“这一轮 Agent 看到了什么”；ExecutionContext 描述“执行外部能力时，
 * 调用者是谁、属于哪个会话、何时超时、如何保证幂等”。把两者分开可以避免把安全字段混进
 * LLM 可自由生成的工具参数。</p>
 *
 * <p>Chat 入口、Action Handler、CapabilityGateway 与 MCP Client/Server 现在都传递这份上下文；
 * {@link #legacy()} 只为不启动完整认证链的纯 Java 单元测试提供匿名上下文。</p>
 */
public final class ExecutionContext {

    private final String traceId;
    private final AuthenticatedPrincipal principal;
    private final String tenantId;
    private final String conversationId;
    private final String idempotencyKey;
    private final Instant deadline;

    public ExecutionContext(String traceId,
                            AuthenticatedPrincipal principal,
                            String tenantId,
                            String conversationId,
                            String idempotencyKey,
                            Instant deadline) {
        this.traceId = traceId == null ? "" : traceId;
        this.principal = Objects.requireNonNull(principal, "principal cannot be null");
        this.tenantId = tenantId == null ? "" : tenantId;
        this.conversationId = conversationId == null ? "" : conversationId;
        this.idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
        this.deadline = deadline;
    }

    /**
     * 不启动 HTTP 认证与 TurnContext 的纯 Java 测试使用的匿名上下文。
     *
     * <p>它不能通过“需要认证”的 Guardrail，也不应出现在生产调用链中。架构门禁会检查生产源码
     * 没有调用本方法，避免测试便利入口变成绕过身份和租户信息的后门。</p>
     */
    public static ExecutionContext legacy() {
        return new ExecutionContext("", AuthenticatedPrincipal.anonymous(), "", "", "", null);
    }

    public String getTraceId() {
        return traceId;
    }

    public AuthenticatedPrincipal getPrincipal() {
        return principal;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getDeadline() {
        return deadline;
    }

    /**
     * 复制当前安全上下文，只替换 deadline。
     *
     * <p>CapabilityGateway 会把上游 Turn deadline 与 CapabilityDefinition 的默认超时取较早值。
     * 其他身份、租户和幂等字段必须原样保留，绝不能让模型或 Provider 在收紧超时时顺便改写它们。</p>
     */
    public ExecutionContext withDeadline(Instant newDeadline) {
        Objects.requireNonNull(newDeadline, "deadline cannot be null");
        if (newDeadline.equals(deadline)) {
            return this;
        }
        return new ExecutionContext(
                traceId,
                principal,
                tenantId,
                conversationId,
                idempotencyKey,
                newDeadline
        );
    }

    public boolean isExpired() {
        return deadline != null && Instant.now().isAfter(deadline);
    }
}
