package com.smartcs.smart_cs_agent.mcp.server;

/**
 * MCP tools/call 中不属于业务 arguments 的可信执行元数据。
 *
 * <p>这些字段会进入 ExecutionContext，绝不能与 LLM 生成的工具参数混在一起。</p>
 */
public final class McpInvocationMetadata {

    private final String requestId;
    private final String authToken;
    private final String userId;
    private final String tenantId;
    private final String conversationId;
    private final String idempotencyKey;

    public McpInvocationMetadata(String requestId,
                                 String authToken,
                                 String userId,
                                 String tenantId,
                                 String conversationId,
                                 String idempotencyKey) {
        this.requestId = safe(requestId);
        this.authToken = safe(authToken);
        this.userId = safe(userId);
        this.tenantId = safe(tenantId);
        this.conversationId = safe(conversationId);
        this.idempotencyKey = safe(idempotencyKey);
    }

    public static McpInvocationMetadata anonymous(String requestId) {
        return new McpInvocationMetadata(requestId, "", "", "", "", "");
    }

    public String getRequestId() {
        return requestId;
    }

    public String getAuthToken() {
        return authToken;
    }

    public String getUserId() {
        return userId;
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

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
