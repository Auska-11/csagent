package com.smartcs.smart_cs_agent.security;

/** HTTP 和 Agent 内部共用的角色名称，避免各处重复手写字符串。 */
public final class SecurityRoles {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String ADMIN = "ADMIN";
    public static final String MCP_CLIENT = "MCP_CLIENT";
    public static final String MCP_TRUSTED_CALLER = "MCP_TRUSTED_CALLER";

    private SecurityRoles() {
    }
}
