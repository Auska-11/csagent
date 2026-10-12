package com.smartcs.smart_cs_agent.controller;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP JSON-RPC 响应对象。
 *
 * JSON-RPC 要求成功时返回 result，失败时返回 error。
 * 这里用静态工厂方法创建，Controller 里读起来更直观。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpJsonRpcResponse {

    private final String jsonrpc = "2.0";
    private final Object id;
    private final Object result;
    private final McpJsonRpcError error;

    private McpJsonRpcResponse(Object id, Object result, McpJsonRpcError error) {
        this.id = id;
        this.result = result;
        this.error = error;
    }

    public static McpJsonRpcResponse success(Object id, Object result) {
        return new McpJsonRpcResponse(id, result, null);
    }

    public static McpJsonRpcResponse error(Object id, int code, String message) {
        return new McpJsonRpcResponse(id, null, new McpJsonRpcError(code, message));
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Object getId() {
        return id;
    }

    public Object getResult() {
        return result;
    }

    public McpJsonRpcError getError() {
        return error;
    }

    public static class McpJsonRpcError {

        private final int code;
        private final String message;

        public McpJsonRpcError(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public int getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }
}
