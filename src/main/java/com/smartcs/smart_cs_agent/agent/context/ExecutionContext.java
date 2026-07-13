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
 * {@link #legacy()} 仅为尚未迁移的旧测试和兼容入口保留。</p>
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
     * 旧 ToolManager 调用没有认证和 traceId 时使用的显式迁移上下文。
     *
     * <p>它不能通过“需要认证”的 Guardrail；后续 Runtime 接入真实身份后会逐步减少调用。</p>
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
