package com.smartcs.smart_cs_agent.mcp.server;

/**
 * MCP tools/call 中不属于业务 arguments 的协议元数据。
 *
 * <p>requestId、conversationId 和 idempotencyKey 可以参与执行上下文构建；
 * authToken、userId、tenantId 是旧协议遗留字段，HTTP 入口绝不能信任它们来建立身份。
 * 当前身份只能来自已经验证的 Authorization Bearer JWT。</p>
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
