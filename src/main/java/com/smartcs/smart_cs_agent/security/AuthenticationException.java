package com.smartcs.smart_cs_agent.security;

/**
 * HTTP/认证适配层使用的稳定认证异常。
 *
 * <p>它把“没有凭据”和“凭据无效”与普通业务参数错误分开，GlobalExceptionHandler 才能返回
 * 正确的 401 状态码。异常消息刻意保持通用，避免告诉攻击者某个 userId 或 token 是否存在。</p>
 */
public final class AuthenticationException extends RuntimeException {

    private final String errorCode;

    private AuthenticationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static AuthenticationException required() {
        return new AuthenticationException("AUTHENTICATION_REQUIRED", "Authentication is required");
    }

    public static AuthenticationException failed() {
        return new AuthenticationException("AUTHENTICATION_FAILED", "Authentication failed");
    }

    public String getErrorCode() {
        return errorCode;
    }
}
