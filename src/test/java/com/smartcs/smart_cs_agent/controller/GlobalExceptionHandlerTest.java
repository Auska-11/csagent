package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.handoff.admin.PendingHandoffAdminCursorException;
import com.smartcs.smart_cs_agent.handoff.admin.PendingHandoffAdminRequestException;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffConflictException;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffVersionConflictException;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketLookupNotFoundException;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketLookupUnavailableException;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketLookupUnavailableReason;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewNotFoundException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void shouldHideMissingAndCrossTenantPendingHandoffAsSameNotFound() {
        ResponseEntity<ApiErrorResponse> response = handler.handlePendingHandoffNotFound(
                new PendingHandoffReviewNotFoundException()
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("PENDING_HANDOFF_NOT_FOUND", body.getErrorCode());
        assertEquals("Pending handoff review record was not found", body.getMessage());
    }

    @Test
    void shouldHandleUnmappedPathAsNotFoundInsteadOfInternalError() {
        ResponseEntity<ApiErrorResponse> response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "/actuator/env")
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("NOT_FOUND", body.getErrorCode());
        assertEquals("Resource not found", body.getMessage());
    }

    @Test
    void shouldHandleSessionVersionConflict() {
        ResponseEntity<ApiErrorResponse> response = handler.handleSessionVersionConflict(
                new SessionVersionConflictException(
                        ConversationKey.defaultTenant("USER-001", "conv-conflict"),
                        1,
                        2
                )
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("SESSION_VERSION_CONFLICT", body.getErrorCode());
        assertEquals("Conversation changed concurrently, please retry", body.getMessage());
    }

    @Test
    void shouldHandlePendingHandoffVersionConflict() {
        ResponseEntity<ApiErrorResponse> response = handler.handlePendingHandoffVersionConflict(
                new PendingHandoffVersionConflictException()
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("PENDING_HANDOFF_VERSION_CONFLICT", body.getErrorCode());
        assertEquals("Pending handoff changed concurrently, please refresh and retry", body.getMessage());
    }

    @Test
    void shouldHandlePendingHandoffStateConflictWithoutExposingRecordIdentity() {
        ResponseEntity<ApiErrorResponse> response = handler.handlePendingHandoffStateConflict(
                new PendingHandoffStateConflictException(PendingHandoffStatus.CONFIRMED)
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("PENDING_HANDOFF_STATE_CONFLICT", body.getErrorCode());
        assertEquals("Pending handoff state does not allow this manual action", body.getMessage());
    }

    @Test
    void shouldHandlePendingHandoffResultConflict() {
        ResponseEntity<ApiErrorResponse> response = handler.handlePendingHandoffResultConflict(
                new PendingHandoffConflictException()
        );

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("PENDING_HANDOFF_RESULT_CONFLICT", response.getBody().getErrorCode());
    }

    @Test
    void shouldDistinguishAuthoritativeRemoteNotFoundFromQueryUnavailable() {
        ResponseEntity<ApiErrorResponse> notFound = handler.handleAfterSalesTicketNotFound(
                new AfterSalesTicketLookupNotFoundException()
        );
        ResponseEntity<ApiErrorResponse> unavailable = handler.handleAfterSalesTicketLookupUnavailable(
                new AfterSalesTicketLookupUnavailableException(
                        AfterSalesTicketLookupUnavailableReason.QUERY_NOT_SUPPORTED
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
        assertNotNull(notFound.getBody());
        assertEquals(
                "PENDING_HANDOFF_REMOTE_TICKET_NOT_FOUND",
                notFound.getBody().getErrorCode()
        );
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertNotNull(unavailable.getBody());
        assertEquals(
                "PENDING_HANDOFF_RESULT_QUERY_UNAVAILABLE",
                unavailable.getBody().getErrorCode()
        );
        assertFalse(unavailable.getBody().getMessage().contains("QUERY_NOT_SUPPORTED"));
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
    void shouldHandleRequestParameterTypeMismatchAsBadRequest() throws Exception {
        Method method = SampleController.class.getDeclaredMethod("list", int.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "not-a-number",
                Integer.class,
                "limit",
                methodParameter,
                new NumberFormatException("must not reach the response")
        );

        ResponseEntity<ApiErrorResponse> response = handler.handleArgumentTypeMismatch(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ApiErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("INVALID_REQUEST_PARAMETER", body.getErrorCode());
        assertEquals("Request parameter is invalid", body.getMessage());
    }

    @Test
    void shouldReturnSpecificSafeErrorsForInvalidPendingHandoffCursorAndRequest() {
        ResponseEntity<ApiErrorResponse> cursor = handler.handlePendingHandoffCursor(
                new PendingHandoffAdminCursorException()
        );
        ResponseEntity<ApiErrorResponse> request = handler.handlePendingHandoffRequest(
                new PendingHandoffAdminRequestException()
        );

        assertEquals(HttpStatus.BAD_REQUEST, cursor.getStatusCode());
        assertNotNull(cursor.getBody());
        assertEquals("INVALID_PENDING_HANDOFF_CURSOR", cursor.getBody().getErrorCode());
        assertEquals("Pending handoff cursor is invalid", cursor.getBody().getMessage());

        assertEquals(HttpStatus.BAD_REQUEST, request.getStatusCode());
        assertNotNull(request.getBody());
        assertEquals("INVALID_PENDING_HANDOFF_REQUEST", request.getBody().getErrorCode());
        assertEquals("Pending handoff review request is invalid", request.getBody().getMessage());
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

        void list(int limit) {
        }
    }
}
