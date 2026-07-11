package com.smartcs.smart_cs_agent.tools;

import com.smartcs.smart_cs_agent.capability.api.CapabilityOutcome;

/**
 * 统一工具返回结果。
 *
 * 【为什么需要 ToolResult？】
 * 之前工具大多直接返回 String。
 * 这样的问题是：
 * - 成功和失败不好区分
 * - 错误码无法统一记录
 * - Trace 里只能看到一段字符串
 * - 后续评测无法稳定判断工具是否成功
 *
 * ToolResult 把工具结果拆成结构化字段：
 * - success：是否成功
 * - toolName：哪个工具返回的
 * - message：给用户或 LLM 看的结果说明
 * - data：结构化数据，当前核心业务工具已经优先返回明确 DTO
 * - errorCode：失败时的错误码
 */
public class ToolResult {

    /** 工具是否执行成功 */
    private final boolean success;

    /** 工具名称 */
    private final String toolName;

    /** 结果说明，通常会交给 LLM 整理成客服回复 */
    private final String message;

    /** 结构化数据。当前核心业务工具已经优先返回明确 DTO，知识库工具返回结构化列表 */
    private final Object data;

    /** 错误码。成功时为 null，失败时例如 TOOL_NOT_FOUND、MISSING_ARGUMENT */
    private final String errorCode;

    /** 新 Capability 三态结果；旧 ToolResult.failure 默认视为技术失败。 */
    private final CapabilityOutcome outcome;

    /** 结果来自哪个 Provider；旧工具直调时为空。 */
    private final String providerId;

    /** 是否由幂等记录回放，true 时 Provider 本次没有再次执行。 */
    private final boolean replayed;

    private ToolResult(boolean success,
                       String toolName,
                       String message,
                       Object data,
                       String errorCode,
                       CapabilityOutcome outcome,
                       String providerId,
                       boolean replayed) {
        this.success = success;
        this.toolName = toolName;
        this.message = message;
        this.data = data;
        this.errorCode = errorCode;
        this.outcome = outcome;
        this.providerId = providerId;
        this.replayed = replayed;
    }

    public static ToolResult success(String toolName, String message, Object data) {
        return new ToolResult(true, toolName, message, data, null, CapabilityOutcome.SUCCESS, null, false);
    }

    public static ToolResult failure(String toolName, String errorCode, String message) {
        return new ToolResult(
                false,
                toolName,
                message,
                null,
                errorCode,
                CapabilityOutcome.TECHNICAL_FAILURE,
                null,
                false
        );
    }

    public static ToolResult fromCapability(boolean success,
                                            String toolName,
                                            String message,
                                            Object data,
                                            String errorCode,
                                            CapabilityOutcome outcome,
                                            String providerId,
                                            boolean replayed) {
        return new ToolResult(
                success,
                toolName,
                message,
                data,
                errorCode,
                outcome,
                providerId,
                replayed
        );
    }

    public boolean isSuccess() {
        return success;
    }

    public String getToolName() {
        return toolName;
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

    public CapabilityOutcome getOutcome() {
        return outcome;
    }

    public boolean isBusinessRejected() {
        return outcome == CapabilityOutcome.BUSINESS_REJECTED;
    }

    public String getProviderId() {
        return providerId;
    }

    public boolean isReplayed() {
        return replayed;
    }

    /**
     * 给 LLM Prompt 或调试输出使用的文本。
     *
     * 注意：真正给前端展示时，优先使用结构化字段；
     * 这个方法主要是为了兼容当前“工具结果 -> LLM 总结”的流程。
     */
    public String toPromptText() {
        if (success) {
            return "Tool: " + toolName + "\n" +
                    "Success: true\n" +
                    "Message:\n" + message;
        }

        return "Tool: " + toolName + "\n" +
                "Success: false\n" +
                "Outcome: " + outcome + "\n" +
                "Error Code: " + errorCode + "\n" +
                "Message:\n" + message;
    }

    @Override
    public String toString() {
        return toPromptText();
    }
}
