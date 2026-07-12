package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.security.AuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局接口异常处理器。
 *
 * 【为什么需要它？】
 * Controller 里可以主动返回 ApiErrorResponse，
 * 但真实接口还会遇到很多“没有手动判断到”的异常，例如：
 * - 前端传来的 JSON 格式不正确
 * - 参数解析失败
 * - 代码里抛出了 IllegalArgumentException
 * - 未预料到的系统异常
 *
 * 如果不统一处理，Spring 可能返回默认错误结构。
 * 对前端和调试来说，错误格式就不稳定。
 *
 * 这里先做轻量第一版：统一返回 ApiErrorResponse。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 认证凭据缺失或无效时统一返回 401。
     *
     * <p>401 和 400 的区别是：401 表示调用者还没有证明“自己是谁”；400 表示身份已经不是问题，
     * 但普通请求参数不合法。前端可以据此决定是否提示重新登录。</p>
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException exception) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(new ApiErrorResponse(exception.getErrorCode(), exception.getMessage()));
    }

    /** 资源不存在时返回稳定 404；越权会话也使用同一响应，避免暴露资源归属。 */
    @ExceptionHandler(ApiNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ApiNotFoundException exception) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("NOT_FOUND", safeMessage(exception.getMessage(), "Resource not found")));
    }

    /**
     * JSON 请求体无法解析时触发。
     *
     * 例如前端传了一个不合法 JSON：
     * { message: "hello"
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("INVALID_REQUEST_BODY", "Request body is not readable"));
    }

    /**
     * Bean Validation 校验失败时触发。
     *
     * 例如 ChatApiRequest.message 上的 @NotBlank 不通过时，
     * Spring 会在进入 Controller 方法之前抛出 MethodArgumentNotValidException。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Request parameter is invalid");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("VALIDATION_ERROR", message));
    }

    /**
     * 业务参数不合法时触发。
     *
     * 后续如果 Service 或 Controller 主动抛 IllegalArgumentException，
     * 会统一返回 BAD_REQUEST。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse("BAD_REQUEST", safeMessage(exception.getMessage(), "Bad request")));
    }

    /**
     * 最后的兜底异常处理。
     *
     * 注意：
     * - 这里不把 exception.getMessage() 直接返回给用户。
     * - 真实项目里异常细节应该写日志，给用户只返回通用错误信息。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse("INTERNAL_ERROR", "Internal server error"));
    }

    private String safeMessage(String message, String fallback) {
        if (message == null || message.isBlank()) {
            return fallback;
        }
        return message;
    }
}
