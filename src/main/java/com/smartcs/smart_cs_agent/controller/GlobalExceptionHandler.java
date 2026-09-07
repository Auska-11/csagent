package com.smartcs.smart_cs_agent.controller;

import com.smartcs.smart_cs_agent.agent.context.ConversationBusyException;
import com.smartcs.smart_cs_agent.handoff.admin.PendingHandoffAdminCursorException;
import com.smartcs.smart_cs_agent.handoff.admin.PendingHandoffAdminRequestException;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffConflictException;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffVersionConflictException;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketLookupNotFoundException;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketLookupUnavailableException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewNotFoundException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import com.smartcs.smart_cs_agent.security.AuthorizationException;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** 已认证用户越权时返回 403，和“还没有登录”的 401 明确区分。 */
    @ExceptionHandler(AuthorizationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthorization(AuthorizationException exception) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ApiErrorResponse("ACCESS_DENIED", exception.getMessage()));
    }

    /** 资源不存在时返回稳定 404；越权会话也使用同一响应，避免暴露资源归属。 */
    @ExceptionHandler(ApiNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ApiNotFoundException exception) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("NOT_FOUND", safeMessage(exception.getMessage(), "Resource not found")));
    }

    /** 当前 JWT 租户内不存在记录和跨租户访问使用完全相同的 404，避免 outboxId 探测。 */
    @ExceptionHandler(PendingHandoffReviewNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handlePendingHandoffNotFound(
            PendingHandoffReviewNotFoundException exception) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        "PENDING_HANDOFF_NOT_FOUND",
                        "Pending handoff review record was not found"
                ));
    }

    /** 未映射的 URL 或静态资源返回 404，不能被兜底处理器误报成服务器 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResource(NoResourceFoundException exception) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse("NOT_FOUND", "Resource not found"));
    }

    /** 多实例同时更新同一会话时返回 409，提醒调用方重新获取最新会话状态。 */
    @ExceptionHandler(SessionVersionConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleSessionVersionConflict(
            SessionVersionConflictException exception) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "SESSION_VERSION_CONFLICT",
                        "Conversation changed concurrently, please retry"
                ));
    }

    /**
     * 同一会话正在处理上一条消息、本次请求在预算内没等到锁时返回 409。
     *
     * <p>此时还没有执行任何业务、没有产生副作用，调用方稍后重试即可；
     * 不能让请求无限排队，也不能把它伪装成 500。</p>
     */
    @ExceptionHandler(ConversationBusyException.class)
    public ResponseEntity<ApiErrorResponse> handleConversationBusy(
            ConversationBusyException exception) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "CONVERSATION_BUSY",
                        "Conversation is currently processing another message, please retry later"
                ));
    }

    /** 管理员页面持有的旧版本不能覆盖并发更新，调用方应刷新记录后重新决定。 */
    @ExceptionHandler(PendingHandoffVersionConflictException.class)
    public ResponseEntity<ApiErrorResponse> handlePendingHandoffVersionConflict(
            PendingHandoffVersionConflictException exception) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "PENDING_HANDOFF_VERSION_CONFLICT",
                        "Pending handoff changed concurrently, please refresh and retry"
                ));
    }

    /** 当前状态不允许人工操作时返回稳定 409，不把记录标识或底层存储细节带入响应。 */
    @ExceptionHandler(PendingHandoffStateConflictException.class)
    public ResponseEntity<ApiErrorResponse> handlePendingHandoffStateConflict(
            PendingHandoffStateConflictException exception) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "PENDING_HANDOFF_STATE_CONFLICT",
                        "Pending handoff state does not allow this manual action"
                ));
    }

    /** 极少数确认结果与已有权威结果不一致时，按业务冲突返回，不能伪装成数据库 500。 */
    @ExceptionHandler(PendingHandoffConflictException.class)
    public ResponseEntity<ApiErrorResponse> handlePendingHandoffResultConflict(
            PendingHandoffConflictException exception) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "PENDING_HANDOFF_RESULT_CONFLICT",
                        "Pending handoff confirmed result conflicts with existing evidence"
                ));
    }

    /** 权威查询成功但远端没有工单；与本地 Outbox 不存在使用不同错误码。 */
    @ExceptionHandler(AfterSalesTicketLookupNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleAfterSalesTicketNotFound(
            AfterSalesTicketLookupNotFoundException exception) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        "PENDING_HANDOFF_REMOTE_TICKET_NOT_FOUND",
                        "No after-sales ticket exists for the pending handoff idempotency key"
                ));
    }

    /**
     * 平台没有查询协议或本次无法得到可信答案时返回 503。
     *
     * <p>不能返回 404；503 明确告诉调用方“系统不知道”，避免管理员据此错误关闭或重新创建工单。</p>
     */
    @ExceptionHandler(AfterSalesTicketLookupUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleAfterSalesTicketLookupUnavailable(
            AfterSalesTicketLookupUnavailableException exception) {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiErrorResponse(
                        "PENDING_HANDOFF_RESULT_QUERY_UNAVAILABLE",
                        "After-sales ticket result query is unavailable"
                ));
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

    /** limit、status 等查询参数类型无法转换时属于客户端错误，不能落入通用 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(
                        "INVALID_REQUEST_PARAMETER",
                        "Request parameter is invalid"
                ));
    }

    /** 游标解析细节不回显；客户端应丢弃该游标并从第一页重新读取。 */
    @ExceptionHandler(PendingHandoffAdminCursorException.class)
    public ResponseEntity<ApiErrorResponse> handlePendingHandoffCursor(
            PendingHandoffAdminCursorException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(
                        "INVALID_PENDING_HANDOFF_CURSOR",
                        "Pending handoff cursor is invalid"
                ));
    }

    /** 已识别的 status、limit、路径或确认结果错误使用稳定的管理接口错误码。 */
    @ExceptionHandler(PendingHandoffAdminRequestException.class)
    public ResponseEntity<ApiErrorResponse> handlePendingHandoffRequest(
            PendingHandoffAdminRequestException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(
                        "INVALID_PENDING_HANDOFF_REQUEST",
                        "Pending handoff review request is invalid"
                ));
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
