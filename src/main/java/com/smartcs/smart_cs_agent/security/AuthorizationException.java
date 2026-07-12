package com.smartcs.smart_cs_agent.security;

/** 调用者已经通过认证，但没有执行当前操作的权限。 */
public final class AuthorizationException extends RuntimeException {

    private AuthorizationException() {
        super("Access is denied");
    }

    public static AuthorizationException denied() {
        return new AuthorizationException();
    }
}
