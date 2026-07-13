package com.smartcs.smart_cs_agent.handoff.admin;

import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffVersionConflictException;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketLookupNotFoundException;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketLookupResult;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketLookupUnavailableException;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketLookupUnavailableReason;
import com.smartcs.smart_cs_agent.handoff.reconciliation.AfterSalesTicketResultLookup;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffEvidenceSource;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionContext;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionResult;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionStore;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditStore;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuthority;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewNotFoundException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewStore;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import com.smartcs.smart_cs_agent.security.AuthorizationException;
import com.smartcs.smart_cs_agent.security.SecurityRoles;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * PendingHandoff 管理用例的应用服务。
 *
 * <p>Controller 只负责 HTTP 参数绑定；这个服务负责可信 Principal、租户隔离、游标绑定、领域命令构造和
 * 安全 DTO 投影。它只依赖复核读写、审计和售后查询四个窄 Port，不知道底层是 Memory、JDBC 还是 HTTP。</p>
 */
@Service
public class PendingHandoffAdminService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");
    private static final Pattern TICKET_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]*");
    private static final Pattern TICKET_STATUS = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");

    private final PendingHandoffReviewStore reviewStore;
    private final PendingHandoffManualDecisionStore decisionStore;
    private final PendingHandoffReviewAuditStore auditStore;
    private final AfterSalesTicketResultLookup ticketResultLookup;
    private final PendingHandoffAdminCursorCodec cursorCodec;
    private final PendingHandoffAdminResponseMapper responseMapper;

    public PendingHandoffAdminService(PendingHandoffReviewStore reviewStore,
                                      PendingHandoffManualDecisionStore decisionStore,
                                      PendingHandoffReviewAuditStore auditStore,
                                      AfterSalesTicketResultLookup ticketResultLookup,
                                      PendingHandoffAdminCursorCodec cursorCodec,
                                      PendingHandoffAdminResponseMapper responseMapper) {
        this.reviewStore = Objects.requireNonNull(reviewStore, "reviewStore cannot be null");
        this.decisionStore = Objects.requireNonNull(decisionStore, "decisionStore cannot be null");
        this.auditStore = Objects.requireNonNull(auditStore, "auditStore cannot be null");
        this.ticketResultLookup = Objects.requireNonNull(
                ticketResultLookup,
                "ticketResultLookup cannot be null"
        );
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec cannot be null");
        this.responseMapper = Objects.requireNonNull(responseMapper, "responseMapper cannot be null");
    }

    /**
     * 查询当前 JWT 租户内指定状态的复核记录。
     *
     * <p>HTTP 游标会先校验版本、用途和 status 绑定，再转换成 Store 的结构化游标；客户端不能通过
     * 复用另一种筛选条件的游标跳转到含义不同的数据集。</p>
     */
    public PendingHandoffAdminListResponse findReviewPage(AuthenticatedPrincipal principal,
                                                           String rawStatus,
                                                           String encodedCursor,
                                                           Integer requestedLimit) {
        requireAdmin(principal);
        PendingHandoffStatus status = parseStatus(rawStatus);
        int limit = boundedLimit(requestedLimit);
        PendingHandoffReviewCursor cursor = cursorCodec.decodeReview(encodedCursor, status);
        PendingHandoffReviewPage page = reviewStore.findReviewPage(
                principal.getTenantId(), status, cursor, limit
        );
        List<PendingHandoffAdminListItemResponse> items = page.records().stream()
                .map(responseMapper::toListItem)
                .toList();
        return new PendingHandoffAdminListResponse(
                items,
                cursorCodec.encodeReview(page.nextCursor(), status),
                page.hasMore()
        );
    }

    /**
     * 读取当前 JWT 租户内的一条安全详情投影。
     *
     * <p>跨租户与真正不存在统一抛出 Not Found；原始领域 Record 不会越过 Mapper 直接进入 JSON。</p>
     */
    public PendingHandoffAdminDetailResponse findDetail(AuthenticatedPrincipal principal,
                                                         String outboxId) {
        requireAdmin(principal);
        return responseMapper.toDetail(requireRecord(principal, outboxId));
    }

    /**
     * 查询一条记录的追加式人工处置时间线。
     *
     * <p>先检查当前租户是否拥有主记录，再查询审计页，才能区分“存在但尚无事件”和“不可见资源”，
     * 同时又不泄露其他租户是否使用了相同 outboxId。</p>
     */
    public PendingHandoffAdminAuditPageResponse findAuditPage(AuthenticatedPrincipal principal,
                                                               String outboxId,
                                                               String encodedCursor,
                                                               Integer requestedLimit) {
        requireAdmin(principal);
        String boundedOutboxId = boundedOutboxId(outboxId);

        // 审计 Store 对“不存在”和“没有历史”都能返回空页。先做租户内详情检查，HTTP 才能稳定区分
        // “资源不存在(404)”与“资源存在但尚无事件(200 [])”，同时不会泄露其他租户的记录。
        requireRecord(principal, boundedOutboxId);
        int limit = boundedLimit(requestedLimit);
        PendingHandoffReviewAuditCursor cursor = cursorCodec.decodeAudit(
                encodedCursor, boundedOutboxId
        );
        PendingHandoffReviewAuditPage page = auditStore.findAuditPage(
                principal.getTenantId(), boundedOutboxId, cursor, limit
        );
        List<PendingHandoffAdminAuditEventResponse> events = page.events().stream()
                .map(responseMapper::toAuditEvent)
                .toList();
        return new PendingHandoffAdminAuditPageResponse(
                events,
                cursorCodec.encodeAudit(page.nextCursor(), boundedOutboxId),
                page.hasMore()
        );
    }

    /**
     * 将待复核记录重新排队，不在 ADMIN 请求线程直接调用售后平台。
     *
     * <p>证据来源固定为 NONE；租户、actor 和 ADMIN 权限全部来自可信 Principal。</p>
     */
    public PendingHandoffAdminDecisionResponse retry(AuthenticatedPrincipal principal,
                                                      String outboxId,
                                                      PendingHandoffAdminDecisionRequest request) {
        requireAdmin(principal);
        requireDecisionRequest(request);
        PendingHandoffManualDecisionResult result = decisionStore.retry(context(
                principal,
                outboxId,
                request.expectedVersion(),
                request.reasonCode(),
                request.decisionNote(),
                PendingHandoffEvidenceSource.NONE
        ));
        return responseMapper.toDecision(result);
    }

    /**
     * 根据管理员已在受信任后台核对到的工单结果确认成功。
     *
     * <p>时间会规范成 ISO-8601 UTC 文本，证据来源固定为 ADMIN_PROVIDED。服务端 reconcile 查询
     * 使用另一种证据来源，不能让客户端通过当前请求体自行伪造。</p>
     */
    public PendingHandoffAdminDecisionResponse confirm(AuthenticatedPrincipal principal,
                                                        String outboxId,
                                                        PendingHandoffAdminConfirmRequest request) {
        requireAdmin(principal);
        requireConfirmRequest(request);
        PendingHandoffAdminConfirmResultRequest confirmed = request.result();
        String normalizedCreatedAt;
        try {
            normalizedCreatedAt = Instant.parse(confirmed.createdAt().trim()).toString();
        } catch (DateTimeParseException invalidTimestamp) {
            throw new PendingHandoffAdminRequestException();
        }
        AfterSalesTicketCreateResult result = new AfterSalesTicketCreateResult(
                confirmed.ticketId().trim(),
                confirmed.status().trim(),
                normalizedCreatedAt
        );
        PendingHandoffManualDecisionResult decision = decisionStore.confirm(context(
                principal,
                outboxId,
                request.expectedVersion(),
                request.reasonCode(),
                request.decisionNote(),
                PendingHandoffEvidenceSource.ADMIN_PROVIDED
        ), result);
        return responseMapper.toDecision(decision);
    }

    /**
     * 使用服务端售后查询 Port 核对原始幂等键，并在查到权威结果时确认记录。
     *
     * <p>查询前先检查页面版本和当前状态，避免拿明显过期的数据调用外部系统。查询后 Store 仍会执行一次
     * 原子 version CAS，因为远端调用期间另一位管理员仍可能完成处置。</p>
     *
     * <p>只有 FOUND 会写状态与审计；NOT_FOUND 和 UNAVAILABLE 是两种不同的无写入结论。</p>
     */
    public PendingHandoffAdminDecisionResponse reconcile(
            AuthenticatedPrincipal principal,
            String outboxId,
            PendingHandoffAdminDecisionRequest request) {
        requireAdmin(principal);
        requireDecisionRequest(request);
        PendingHandoffRecord current = requireRecord(principal, outboxId);
        requireReconcilable(current, request.expectedVersion());

        AfterSalesTicketLookupResult lookup = ticketResultLookup.findByIdempotencyKey(current.key());
        if (lookup == null) {
            throw new AfterSalesTicketLookupUnavailableException(
                    AfterSalesTicketLookupUnavailableReason.INVALID_RESPONSE
            );
        }

        return switch (lookup.outcome()) {
            case FOUND -> {
                AfterSalesTicketCreateResult verified = normalizeLookupResult(lookup.ticket());
                PendingHandoffManualDecisionResult decision = decisionStore.confirm(context(
                        principal,
                        current.outboxId(),
                        request.expectedVersion(),
                        request.reasonCode(),
                        request.decisionNote(),
                        PendingHandoffEvidenceSource.AFTER_SALES_IDEMPOTENCY_QUERY
                ), verified);
                yield responseMapper.toDecision(decision);
            }
            case NOT_FOUND -> throw new AfterSalesTicketLookupNotFoundException();
            case UNAVAILABLE -> throw new AfterSalesTicketLookupUnavailableException(
                    lookup.unavailableReason()
            );
        };
    }

    /**
     * 将待复核记录人工关闭为不可自动恢复的终态。
     *
     * <p>关闭不代表远端成功，所以不会构造工单结果，证据来源也固定为 NONE。</p>
     */
    public PendingHandoffAdminDecisionResponse close(AuthenticatedPrincipal principal,
                                                      String outboxId,
                                                      PendingHandoffAdminDecisionRequest request) {
        requireAdmin(principal);
        requireDecisionRequest(request);
        PendingHandoffManualDecisionResult result = decisionStore.close(context(
                principal,
                outboxId,
                request.expectedVersion(),
                request.reasonCode(),
                request.decisionNote(),
                PendingHandoffEvidenceSource.NONE
        ));
        return responseMapper.toDecision(result);
    }

    private PendingHandoffRecord requireRecord(AuthenticatedPrincipal principal, String outboxId) {
        String boundedOutboxId = boundedOutboxId(outboxId);
        return reviewStore.findByTenantAndOutboxId(principal.getTenantId(), boundedOutboxId)
                .orElseThrow(PendingHandoffReviewNotFoundException::new);
    }

    private PendingHandoffManualDecisionContext context(
            AuthenticatedPrincipal principal,
            String outboxId,
            Long expectedVersion,
            String reasonCode,
            String decisionNote,
            PendingHandoffEvidenceSource evidenceSource) {
        requireExpectedVersion(expectedVersion);
        requireReasonCode(reasonCode);
        requireNote(decisionNote);
        try {
            return new PendingHandoffManualDecisionContext(
                    principal.getTenantId(),
                    boundedOutboxId(outboxId),
                    expectedVersion,
                    principal.getUserId(),
                    PendingHandoffReviewAuthority.ADMIN,
                    reasonCode,
                    decisionNote,
                    evidenceSource
            );
        } catch (IllegalArgumentException invalidDomainInput) {
            throw new PendingHandoffAdminRequestException();
        }
    }

    private void requireReconcilable(PendingHandoffRecord current, long expectedVersion) {
        // version 必须优先于 status 判断，与 Memory/JDBC Store 的冲突优先级保持一致。
        if (current.version() != expectedVersion) {
            throw new PendingHandoffVersionConflictException();
        }
        if (current.status() != PendingHandoffStatus.NEEDS_MANUAL_REVIEW) {
            throw new PendingHandoffStateConflictException(current.status());
        }
    }

    private AfterSalesTicketCreateResult normalizeLookupResult(AfterSalesTicketCreateResult result) {
        if (result == null) {
            throw new AfterSalesTicketLookupUnavailableException(
                    AfterSalesTicketLookupUnavailableReason.INVALID_RESPONSE
            );
        }
        try {
            requireBoundedPattern(result.ticketId(), 128, TICKET_ID);
            requireBoundedPattern(result.status(), 64, TICKET_STATUS);
            requireBoundedText(result.createdAt(), 128);
            return new AfterSalesTicketCreateResult(
                    result.ticketId().trim(),
                    result.status().trim(),
                    Instant.parse(result.createdAt().trim()).toString()
            );
        } catch (PendingHandoffAdminRequestException | DateTimeParseException invalidRemoteResult) {
            // 远端响应不合法是依赖不可用，不是管理员提交了错误参数，因此不能错误返回客户端 400。
            throw new AfterSalesTicketLookupUnavailableException(
                    AfterSalesTicketLookupUnavailableReason.INVALID_RESPONSE
            );
        }
    }

    private void requireAdmin(AuthenticatedPrincipal principal) {
        if (principal == null || !principal.isAuthenticated()) {
            throw AuthenticationException.required();
        }
        if (!principal.hasRole(SecurityRoles.ADMIN)) {
            throw AuthorizationException.denied();
        }
    }

    private PendingHandoffStatus parseStatus(String rawStatus) {
        String value = rawStatus == null || rawStatus.isBlank()
                ? PendingHandoffStatus.NEEDS_MANUAL_REVIEW.name()
                : rawStatus.trim().toUpperCase(Locale.ROOT);
        try {
            return PendingHandoffStatus.valueOf(value);
        } catch (IllegalArgumentException unknownStatus) {
            throw new PendingHandoffAdminRequestException();
        }
    }

    private int boundedLimit(Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new PendingHandoffAdminRequestException();
        }
        return limit;
    }

    private String boundedOutboxId(String outboxId) {
        if (outboxId == null || outboxId.isBlank()) {
            throw new PendingHandoffAdminRequestException();
        }
        String trimmed = outboxId.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > 64) {
            throw new PendingHandoffAdminRequestException();
        }
        return trimmed;
    }

    private void requireDecisionRequest(PendingHandoffAdminDecisionRequest request) {
        if (request == null) {
            throw new PendingHandoffAdminRequestException();
        }
        requireExpectedVersion(request.expectedVersion());
        requireReasonCode(request.reasonCode());
        requireNote(request.decisionNote());
    }

    private void requireConfirmRequest(PendingHandoffAdminConfirmRequest request) {
        if (request == null || request.result() == null) {
            throw new PendingHandoffAdminRequestException();
        }
        requireExpectedVersion(request.expectedVersion());
        requireReasonCode(request.reasonCode());
        requireNote(request.decisionNote());
        requireBoundedPattern(request.result().ticketId(), 128, TICKET_ID);
        requireBoundedPattern(request.result().status(), 64, TICKET_STATUS);
        requireBoundedText(request.result().createdAt(), 128);
    }

    private void requireExpectedVersion(Long expectedVersion) {
        if (expectedVersion == null || expectedVersion < 0) {
            throw new PendingHandoffAdminRequestException();
        }
    }

    private void requireReasonCode(String reasonCode) {
        requireBoundedText(reasonCode, 64);
        if (!REASON_CODE.matcher(reasonCode).matches()) {
            throw new PendingHandoffAdminRequestException();
        }
    }

    private void requireNote(String note) {
        if (note != null && note.codePointCount(0, note.length()) > 1000) {
            throw new PendingHandoffAdminRequestException();
        }
    }

    private void requireBoundedPattern(String value, int maxCodePoints, Pattern pattern) {
        requireBoundedText(value, maxCodePoints);
        if (!pattern.matcher(value).matches()) {
            throw new PendingHandoffAdminRequestException();
        }
    }

    private void requireBoundedText(String value, int maxCodePoints) {
        if (value == null || value.isBlank()) {
            throw new PendingHandoffAdminRequestException();
        }
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > maxCodePoints) {
            throw new PendingHandoffAdminRequestException();
        }
    }
}
