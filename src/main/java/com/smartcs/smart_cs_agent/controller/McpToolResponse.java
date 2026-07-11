package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.capability.api.CapabilityResult;
import com.smartcs.smart_cs_agent.tools.ToolResult;

/**
 * MCP Adapter 工具调用响应。
 *
 * 这个类是给 HTTP / MCP Adapter 层看的响应 DTO。
 * 它把内部 ToolResult 映射成稳定的外部字段，方便前端、调试工具或未来 MCP Client 读取。
 */
public class McpToolResponse {

    /** 工具是否执行成功 */
    private final boolean success;

    /** 工具名称 */
    private final String toolName;

    /** 给人或 LLM 看的结果说明 */
    private final String message;

    /** 结构化工具数据 */
    private final Object data;

    /** 失败时的错误码，成功时为 null */
    private final String errorCode;

    private final String outcome;
    private final String providerId;
    private final boolean replayed;

    private McpToolResponse(boolean success,
                            String toolName,
                            String message,
                            Object data,
                            String errorCode,
                            String outcome,
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

    public static McpToolResponse from(ToolResult result) {
        return new McpToolResponse(
                result.isSuccess(),
                result.getToolName(),
                result.getMessage(),
                result.getData(),
                result.getErrorCode(),
                result.getOutcome().name(),
                result.getProviderId(),
                result.isReplayed()
        );
    }

    public static McpToolResponse from(CapabilityResult<?> result) {
        return new McpToolResponse(
                result.isSuccess(),
                result.getCapabilityId(),
                result.getMessage(),
                result.getData(),
                result.getErrorCode(),
                result.getOutcome().name(),
                result.getProviderId(),
                result.isReplayed()
        );
    }

    public static McpToolResponse failure(String toolName, String errorCode, String message) {
        return new McpToolResponse(false, toolName, message, null, errorCode, "TECHNICAL_FAILURE", null, false);
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

    public String getOutcome() {
        return outcome;
    }

    public String getProviderId() {
        return providerId;
    }

    public boolean isReplayed() {
        return replayed;
    }
}
