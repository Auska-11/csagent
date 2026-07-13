package com.smartcs.smart_cs_agent.controller;

import java.util.Collections;
import java.util.Map;

/**
 * MCP Adapter 工具调用请求。
 *
 * 【为什么不直接复用 CapabilityCall？】
 * McpToolCallRequest 是 HTTP 接口入参，CapabilityCall 是 Agent 内部的传输无关请求，两者属于不同边界。
 *
 * 两者字段目前很像，但职责不同：
 * - McpToolCallRequest：负责接收前端或外部调用方传来的 JSON。
 * - CapabilityCall：由 MCP Server Adapter 校验元数据后交给 Gateway 执行。
 *
 * 这样分开后，后续真正迁移成独立 MCP Server 时，外部协议可以继续演进，
 * 不会把内部 CapabilityCall 结构绑死。
 */
public class McpToolCallRequest {

    /** Capability 名称，例如 order.lookup；旧下划线别名仍由兼容层规范化。 */
    private String name;

    /** 工具参数，例如 {"order_id": "ORD-001"} */
    private Map<String, Object> arguments = Collections.emptyMap();

    /** requestId、conversationId、idempotencyKey 属于 MCP 执行元数据，不会传入业务 arguments。 */
    private String requestId;

    /** 旧协议字段：HTTP 认证只读取 Authorization Header，这里的 token 永远不能建立身份。 */
    private String authToken;

    /** 旧协议字段：只允许做一致性检查，不能覆盖 JWT Principal。 */
    private String userId;

    /** 旧协议字段：只允许做一致性检查，不能覆盖 JWT Principal。 */
    private String tenantId;
    private String conversationId;
    private String idempotencyKey;

    public McpToolCallRequest() {
    }

    public McpToolCallRequest(String name, Map<String, Object> arguments) {
        this.name = name;
        this.arguments = arguments == null ? Collections.emptyMap() : arguments;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> getArguments() {
        return arguments == null ? Collections.emptyMap() : arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments == null ? Collections.emptyMap() : arguments;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
