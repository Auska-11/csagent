package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.security.AuthenticationException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局异常处理测试。
 *
 * 这个测试不启动 Spring 容器，直接调用异常处理方法。
 * 目标是验证：不同异常最终都会被包装成稳定的 ApiErrorResponse。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldHandleUnreadableRequestBody() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnreadableMessage(
                new HttpMessageNotReadableException("bad json", (HttpInputMessage) null)
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("INVALID_REQUEST_BODY", body.getErrorCode());
        assertEquals("Request body is not readable", body.getMessage());
        assertTrue(body.getTimestamp() > 0);
    }

    @Test
    void shouldHandleAuthenticationFailureAsUnauthorized() {
        ResponseEntity<ApiErrorResponse> response = handler.handleAuthentication(
                AuthenticationException.failed()
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("AUTHENTICATION_FAILED", body.getErrorCode());
        assertEquals("Authentication failed", body.getMessage());
    }

    @Test
    void shouldHandleMissingResourceAsNotFound() {
        ResponseEntity<ApiErrorResponse> response = handler.handleNotFound(
                new ApiNotFoundException("Conversation not found")
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("NOT_FOUND", body.getErrorCode());
        assertEquals("Conversation not found", body.getMessage());
    }

    @Test
    void shouldHandleValidationException() throws Exception {
        Method method = SampleController.class.getDeclaredMethod("send", ChatApiRequest.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new ChatApiRequest(), "chatApiRequest");
        bindingResult.addError(new FieldError("chatApiRequest", "message", "Message cannot be empty"));

        ResponseEntity<ApiErrorResponse> response = handler.handleValidationException(
                new MethodArgumentNotValidException(methodParameter, bindingResult)
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("VALIDATION_ERROR", body.getErrorCode());
        assertEquals("Message cannot be empty", body.getMessage());
    }

    @Test
    void shouldHandleIllegalArgument() {
        ResponseEntity<ApiErrorResponse> response = handler.handleIllegalArgument(
                new IllegalArgumentException("userId is invalid")
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("BAD_REQUEST", body.getErrorCode());
        assertEquals("userId is invalid", body.getMessage());
    }

    @Test
    void shouldHandleUnexpectedExceptionWithoutExposingDetails() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpectedException(
                new RuntimeException("database password leaked in stack trace")
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("INTERNAL_ERROR", body.getErrorCode());
        assertEquals("Internal server error", body.getMessage());
    }

    private static class SampleController {

        void send(ChatApiRequest request) {
        }
    }
}
