package com.smartcs.smart_cs_agent.agent.context;

import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;

/**
 * Workflow 可读取的标准化 Capability 执行结果。
 *
 * <p><strong>为什么不让新 Workflow 直接读取旧 {@code ToolResult}？</strong>
 * ToolResult 属于早期本地工具层；如果 Workflow 依赖它，就会在换成远程 MCP Provider 时
 * 继续背着旧工具概念。CapabilityObservation 只保存稳定的 Capability 契约字段，因此结果来自
 * 本地 Java Provider、远程 MCP，还是测试 Fake，对 Workflow 都没有区别。</p>
 *
 * <p>调用链：CapabilityActionHandler -> TurnContext -> AgentWorkflow。</p>
 *
 * <p>本类只是一份不可变的“观察结果”，不负责重试、Provider 选择、权限判断或业务状态迁移。</p>
 */
public final class CapabilityObservation {

    private final String capabilityId;
    private final CapabilityOutcome outcome;
    private final String message;
    private final Object data;
    private final String errorCode;
    private final String providerId;
    private final boolean replayed;

    public CapabilityObservation(String capabilityId,
                                 CapabilityOutcome outcome,
                                 String message,
                                 Object data,
                                 String errorCode,
                                 String providerId,
                                 boolean replayed) {
        if (capabilityId == null || capabilityId.isBlank()) {
            throw new IllegalArgumentException("capabilityId cannot be empty");
        }
        this.capabilityId = capabilityId;
        this.outcome = outcome == null ? CapabilityOutcome.TECHNICAL_FAILURE : outcome;
        this.message = message == null ? "" : message;
        this.data = data;
        this.errorCode = errorCode;
        this.providerId = providerId;
        this.replayed = replayed;
    }

    public String getCapabilityId() {
        return capabilityId;
    }

    public CapabilityOutcome getOutcome() {
        return outcome;
    }

    public boolean isSuccess() {
        return outcome == CapabilityOutcome.SUCCESS;
    }

    public boolean isBusinessRejected() {
        return outcome == CapabilityOutcome.BUSINESS_REJECTED;
    }

    public String getMessage() {
        return message;
    }

    public Object getData() {
        return data;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getProviderId() {
        return providerId;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
