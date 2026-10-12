package com.smartcs.smart_cs_agent.controller;

import java.util.Collections;
import java.util.Map;

/**
 * MCP JSON-RPC 请求对象。
 *
 * 【为什么还要新增这个类？】
 * 之前的 /api/mcp/tools 是 REST 风格 Adapter；
 * 真正 MCP Client 更常见的是 JSON-RPC 风格：
 *
 * {
 *   "jsonrpc": "2.0",
 *   "id": 1,
 *   "method": "tools/list",
 *   "params": {}
 * }
 *
 * 所以这里单独建一个请求 DTO，避免把 REST Adapter 和 MCP Server 协议揉在一起。
 */
public class McpJsonRpcRequest {

    private String jsonrpc;
    private Object id;
    private boolean idPresent;
    private String method;
    private Map<String, Object> params = Collections.emptyMap();

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
        this.idPresent = true;
    }

    /** 显式 id:null 仍是请求；只有省略 id 才是 JSON-RPC notification。 */
    public boolean hasId() {
        return idPresent;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Map<String, Object> getParams() {
        return params == null ? Collections.emptyMap() : params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params == null ? Collections.emptyMap() : params;
    }
}
