package com.smartcs.smart_cs_agent.capability.api;

import java.util.Objects;

/**
 * Capability Provider 返回给 Agent 内核的统一结果。
 *
 * <p>providerId 能帮助 Trace 说明结果来自本地工具还是远程 MCP。errorCode 应是稳定机器码；
 * message 是给 Workflow 或回复生成器参考的安全说明，不能直接放入数据库异常堆栈。</p>
 */
public final class CapabilityResult<T> {

    private final CapabilityOutcome outcome;
    private final String capabilityId;
    private final String providerId;
    private final String message;
    private final T data;
    private final String errorCode;
    private final boolean replayed;

    private CapabilityResult(CapabilityOutcome outcome,
                             String capabilityId,
                             String providerId,
                             String message,
                             T data,
                             String errorCode,
                             boolean replayed) {
        this.outcome = Objects.requireNonNull(outcome, "outcome cannot be null");
        this.capabilityId = requireText(capabilityId, "capabilityId cannot be empty");
        this.providerId = requireText(providerId, "providerId cannot be empty");
        this.message = message == null ? "" : message;
        this.data = data;
        this.errorCode = errorCode;
        this.replayed = replayed;
    }

    public static <T> CapabilityResult<T> success(String capabilityId,
                                                   String providerId,
                                                   String message,
                                                   T data) {
        return new CapabilityResult<>(
                CapabilityOutcome.SUCCESS,
                capabilityId,
                providerId,
                message,
                data,
                null,
                false
        );
    }

    public static <T> CapabilityResult<T> businessRejected(String capabilityId,
                                                            String providerId,
                                                            String errorCode,
                                                            String message,
                                                            T data) {
        return new CapabilityResult<>(
                CapabilityOutcome.BUSINESS_REJECTED,
                capabilityId,
                providerId,
                message,
                data,
                requireText(errorCode, "Business rejection errorCode cannot be empty"),
                false
        );
    }

    public static <T> CapabilityResult<T> technicalFailure(String capabilityId,
                                                            String providerId,
                                                            String errorCode,
                                                            String message) {
        return new CapabilityResult<>(
                CapabilityOutcome.TECHNICAL_FAILURE,
                capabilityId,
                providerId,
                message,
                null,
                requireText(errorCode, "Technical failure errorCode cannot be empty"),
                false
        );
    }

    public CapabilityOutcome getOutcome() {
        return outcome;
    }

    public String getCapabilityId() {
        return capabilityId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isSuccess() {
        return outcome == CapabilityOutcome.SUCCESS;
    }

    /** true 表示结果来自幂等记录回放，Provider 本次没有再次执行。 */
    public boolean isReplayed() {
        return replayed;
    }

    /** 复制同一个结构化结果，并标记为幂等回放。 */
    public CapabilityResult<T> asReplayed() {
        return new CapabilityResult<>(
                outcome,
                capabilityId,
                providerId,
                message,
                data,
                errorCode,
                true
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
