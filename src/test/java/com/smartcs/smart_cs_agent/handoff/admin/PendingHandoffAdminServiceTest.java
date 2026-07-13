package com.smartcs.smart_cs_agent.handoff.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.AuthenticatedPrincipal;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffEvidenceSource;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualAction;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionContext;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionResult;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionStore;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditEvent;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditStore;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuthority;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewNotFoundException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewStore;
import com.smartcs.smart_cs_agent.security.AuthenticationException;
import com.smartcs.smart_cs_agent.security.AuthorizationException;
import com.smartcs.smart_cs_agent.security.SecurityRoles;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 20.4 应用服务的 Principal、租户、游标和命令编排测试。 */
class PendingHandoffAdminServiceTest {

    private PendingHandoffReviewStore reviewStore;
    private PendingHandoffManualDecisionStore decisionStore;
    private PendingHandoffReviewAuditStore auditStore;
    private PendingHandoffAdminCursorCodec cursorCodec;
    private PendingHandoffAdminService service;

    @BeforeEach
    void setUp() {
        reviewStore = mock(PendingHandoffReviewStore.class);
        decisionStore = mock(PendingHandoffManualDecisionStore.class);
        auditStore = mock(PendingHandoffReviewAuditStore.class);
        cursorCodec = new PendingHandoffAdminCursorCodec(new ObjectMapper());
        service = new PendingHandoffAdminService(
                reviewStore,
                decisionStore,
                auditStore,
                cursorCodec,
                new PendingHandoffAdminResponseMapper()
        );
    }

    @Test
    void shouldRejectUntrustedPrincipalBeforeAnyStoreAccess() {
        AuthenticatedPrincipal customer = AuthenticatedPrincipal.authenticated(
                "tenant-a", "customer-1", Set.of(SecurityRoles.CUSTOMER)
        );

        assertThrows(AuthenticationException.class,
                () -> service.findReviewPage(null, null, null, null));
        assertThrows(AuthorizationException.class,
                () -> service.findReviewPage(customer, null, null, null));

        verifyNoInteractions(reviewStore, decisionStore, auditStore);
    }

    @Test
    void shouldPageOnlyPrincipalTenantAndBindOpaqueCursorToStatus() {
        PendingHandoffRecord record = record("tenant-a", "outbox-list", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, 3);
        PendingHandoffReviewCursor next = new PendingHandoffReviewCursor(900, "outbox-list");
        when(reviewStore.findReviewPage(
                "tenant-a", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, null, 1
        )).thenReturn(new PendingHandoffReviewPage(List.of(record), next, true));

        PendingHandoffAdminListResponse response = service.findReviewPage(
                admin("tenant-a", "admin-a"), "needs_manual_review", null, 1
        );

        assertTrue(response.hasMore());
        assertEquals(1, response.items().size());
        assertNotNull(response.nextCursor());
        assertEquals(next, cursorCodec.decodeReview(
                response.nextCursor(), PendingHandoffStatus.NEEDS_MANUAL_REVIEW
        ));
        assertThrows(PendingHandoffAdminCursorException.class, () -> cursorCodec.decodeReview(
                response.nextCursor(), PendingHandoffStatus.CONFIRMED
        ));
    }

    @Test
    void shouldHideCrossTenantDetailAndAuditAsSameNotFound() {
        AuthenticatedPrincipal tenantB = admin("tenant-b", "admin-b");
        when(reviewStore.findByTenantAndOutboxId("tenant-b", "outbox-a"))
                .thenReturn(Optional.empty());

        assertThrows(PendingHandoffReviewNotFoundException.class,
                () -> service.findDetail(tenantB, "outbox-a"));
        assertThrows(PendingHandoffReviewNotFoundException.class,
                () -> service.findAuditPage(tenantB, "outbox-a", null, 10));

        verify(reviewStore, never()).findByTenantAndOutboxId("tenant-a", "outbox-a");
        verifyNoInteractions(auditStore);
    }

    @Test
    void shouldCheckResourceThenPageTenantScopedAudit() {
        PendingHandoffRecord record = record("tenant-a", "outbox-audit", PendingHandoffStatus.MANUALLY_CLOSED, 4);
        PendingHandoffReviewAuditEvent event = audit(
                record, PendingHandoffManualAction.CLOSE, PendingHandoffStatus.MANUALLY_CLOSED,
                PendingHandoffEvidenceSource.NONE, 3, 4
        );
        PendingHandoffReviewAuditCursor next = new PendingHandoffReviewAuditCursor(1_000, "audit-1");
        when(reviewStore.findByTenantAndOutboxId("tenant-a", "outbox-audit"))
                .thenReturn(Optional.of(record));
        when(auditStore.findAuditPage("tenant-a", "outbox-audit", null, 1))
                .thenReturn(new PendingHandoffReviewAuditPage(List.of(event), next, true));

        PendingHandoffAdminAuditPageResponse response = service.findAuditPage(
                admin("tenant-a", "admin-a"), "outbox-audit", null, 1
        );

        assertEquals(1, response.events().size());
        assertNotNull(response.nextCursor());
        assertEquals(next, cursorCodec.decodeAudit(response.nextCursor(), "outbox-audit"));
        InOrder order = inOrder(reviewStore, auditStore);
        order.verify(reviewStore).findByTenantAndOutboxId("tenant-a", "outbox-audit");
        order.verify(auditStore).findAuditPage("tenant-a", "outbox-audit", null, 1);
    }

    @Test
    void shouldBuildAllManualContextsFromTrustedPrincipal() {
        AuthenticatedPrincipal principal = admin("tenant-trusted", "admin-trusted");
        PendingHandoffRecord source = record(
                "tenant-trusted", "outbox-decision", PendingHandoffStatus.NEEDS_MANUAL_REVIEW, 3
        );
        PendingHandoffManualDecisionResult retryResult = decision(
                source, PendingHandoffManualAction.RETRY, PendingHandoffStatus.RETRY_SCHEDULED,
                PendingHandoffEvidenceSource.NONE, null
        );
        PendingHandoffManualDecisionResult confirmResult = decision(
                source, PendingHandoffManualAction.CONFIRM, PendingHandoffStatus.CONFIRMED,
                PendingHandoffEvidenceSource.ADMIN_PROVIDED,
                new AfterSalesTicketCreateResult("AS-1", "PENDING", "2026-07-13T00:00:00Z")
        );
        PendingHandoffManualDecisionResult closeResult = decision(
                source, PendingHandoffManualAction.CLOSE, PendingHandoffStatus.MANUALLY_CLOSED,
                PendingHandoffEvidenceSource.NONE, null
        );
        when(decisionStore.retry(any())).thenReturn(retryResult);
        when(decisionStore.confirm(any(), any())).thenReturn(confirmResult);
        when(decisionStore.close(any())).thenReturn(closeResult);

        PendingHandoffAdminDecisionRequest common = new PendingHandoffAdminDecisionRequest(
                3L, "ADMIN_DECISION", "手机号 13800138000"
        );
        service.retry(principal, "outbox-decision", common);
        service.confirm(principal, "outbox-decision", new PendingHandoffAdminConfirmRequest(
                3L,
                "REMOTE_VERIFIED",
                "人工后台核对",
                new PendingHandoffAdminConfirmResultRequest(
                        "AS-1", "PENDING", "2026-07-13T00:00:00Z"
                )
        ));
        service.close(principal, "outbox-decision", common);

        ArgumentCaptor<PendingHandoffManualDecisionContext> retry =
                ArgumentCaptor.forClass(PendingHandoffManualDecisionContext.class);
        ArgumentCaptor<PendingHandoffManualDecisionContext> confirm =
                ArgumentCaptor.forClass(PendingHandoffManualDecisionContext.class);
        ArgumentCaptor<AfterSalesTicketCreateResult> confirmedResult =
                ArgumentCaptor.forClass(AfterSalesTicketCreateResult.class);
        ArgumentCaptor<PendingHandoffManualDecisionContext> close =
                ArgumentCaptor.forClass(PendingHandoffManualDecisionContext.class);
        verify(decisionStore).retry(retry.capture());
        verify(decisionStore).confirm(confirm.capture(), confirmedResult.capture());
        verify(decisionStore).close(close.capture());

        for (PendingHandoffManualDecisionContext captured : List.of(
                retry.getValue(), confirm.getValue(), close.getValue())) {
            assertEquals("tenant-trusted", captured.tenantId());
            assertEquals("admin-trusted", captured.actorId());
            assertEquals(PendingHandoffReviewAuthority.ADMIN, captured.authorityUsed());
            assertFalse(captured.decisionNote().contains("13800138000"));
        }
        assertEquals(PendingHandoffEvidenceSource.NONE, retry.getValue().evidenceSource());
        assertEquals(PendingHandoffEvidenceSource.ADMIN_PROVIDED, confirm.getValue().evidenceSource());
        assertEquals(PendingHandoffEvidenceSource.NONE, close.getValue().evidenceSource());
        assertEquals("2026-07-13T00:00:00Z", confirmedResult.getValue().createdAt());
    }

    @Test
    void shouldRejectInvalidFiltersCursorAndConfirmationBeforeWrite() {
        AuthenticatedPrincipal admin = admin("tenant-a", "admin-a");
        assertThrows(PendingHandoffAdminRequestException.class,
                () -> service.findReviewPage(admin, "NOT_A_STATUS", null, 20));
        assertThrows(PendingHandoffAdminRequestException.class,
                () -> service.findReviewPage(admin, null, null, 0));
        assertThrows(PendingHandoffAdminCursorException.class,
                () -> service.findReviewPage(admin, null, "broken***", 20));
        assertThrows(PendingHandoffAdminRequestException.class, () -> service.confirm(
                admin,
                "outbox-1",
                new PendingHandoffAdminConfirmRequest(
                        3L,
                        "REMOTE_VERIFIED",
                        "note",
                        new PendingHandoffAdminConfirmResultRequest(
                                "AS-1", "PENDING", "not-an-instant"
                        )
                )
        ));

        verifyNoInteractions(decisionStore);
    }

    private AuthenticatedPrincipal admin(String tenantId, String actorId) {
        return AuthenticatedPrincipal.authenticated(
                tenantId, actorId, Set.of(SecurityRoles.ADMIN)
        );
    }

    private PendingHandoffRecord record(String tenantId,
                                        String outboxId,
                                        PendingHandoffStatus status,
                                        long version) {
        return record(tenantId, outboxId, status, version, null);
    }

    private PendingHandoffRecord record(String tenantId,
                                        String outboxId,
                                        PendingHandoffStatus status,
                                        long version,
                                        AfterSalesTicketCreateResult confirmedResult) {
        return new PendingHandoffRecord(
                outboxId,
                tenantId,
                "private-user@example.com",
                "private-conversation",
                "COMPLAINT",
                "联系 13800138000，token=secret-value",
                new AfterSalesTicketCreateRequest(
                        "private title",
                        "private description",
                        "PRIVATE-CUSTOMER",
                        "general",
                        "complaint",
                        "HIGH",
                        "PRIVATE-IDEMPOTENCY"
                ),
                status,
                "SocketTimeoutException",
                confirmedResult,
                2,
                status == PendingHandoffStatus.RETRY_SCHEDULED ? 1_000 : Long.MAX_VALUE,
                version,
                100,
                900
        );
    }

    private PendingHandoffReviewAuditEvent audit(PendingHandoffRecord source,
                                                 PendingHandoffManualAction action,
                                                 PendingHandoffStatus target,
                                                 PendingHandoffEvidenceSource evidence,
                                                 long fromVersion,
                                                 long toVersion) {
        return new PendingHandoffReviewAuditEvent(
                "audit-1",
                source.tenantId(),
                source.outboxId(),
                action,
                "admin-trusted",
                PendingHandoffReviewAuthority.ADMIN,
                "ADMIN_DECISION",
                "safe note",
                evidence,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                target,
                fromVersion,
                toVersion,
                "SocketTimeoutException",
                source.attemptCount(),
                action == PendingHandoffManualAction.RETRY ? 1_000L : null,
                1_000
        );
    }

    private PendingHandoffManualDecisionResult decision(
            PendingHandoffRecord source,
            PendingHandoffManualAction action,
            PendingHandoffStatus target,
            PendingHandoffEvidenceSource evidence,
            AfterSalesTicketCreateResult confirmedResult) {
        PendingHandoffRecord updated = record(
                source.tenantId(), source.outboxId(), target, source.version() + 1, confirmedResult
        );
        return new PendingHandoffManualDecisionResult(
                updated,
                audit(source, action, target, evidence, source.version(), source.version() + 1)
        );
    }
}
