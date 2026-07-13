package com.smartcs.smart_cs_agent.handoff.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffEvidenceSource;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualAction;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionResult;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditEvent;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuthority;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PendingHandoffAdminResponseMapperTest {

    private static final String TENANT_SECRET = "tenant-secret";
    private static final String USER_SECRET = "user-secret";
    private static final String CONVERSATION_SECRET = "conversation-secret";
    private static final String IDEMPOTENCY_SECRET = "idempotency-secret";
    private static final String REQUEST_TITLE_SECRET = "request-title-secret";
    private static final String REQUEST_DESCRIPTION_SECRET = "request-description-secret";
    private static final String CUSTOMER_SECRET = "customer-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PendingHandoffAdminResponseMapper mapper = new PendingHandoffAdminResponseMapper();

    @Test
    void shouldMapListItemWithoutBulkFreeTextRequestOrIdentityAndHideMaxSentinel() {
        PendingHandoffRecord record = record(
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                null,
                Long.MAX_VALUE,
                7
        );

        PendingHandoffAdminListItemResponse response = mapper.toListItem(record);
        JsonNode json = objectMapper.valueToTree(response);

        assertEquals(Set.of(
                "outboxId", "status", "handoffReasonCode", "failureCode", "attemptCount",
                "scheduledAt", "version", "createdAt", "updatedAt"
        ), fields(json));
        assertNull(response.scheduledAt());
        assertEquals("USER_REQUESTED_HUMAN", response.handoffReasonCode());
        assertNoRecordSecrets(json.toString());
        assertFalse(json.toString().contains("summaryPreview"));
        assertFalse(json.toString().contains("confirmedTicket"));
    }

    @Test
    void shouldMapDetailToRedactedPreviewAllowlistedRequestMetadataAndTicketProjection() {
        PendingHandoffRecord record = record(
                PendingHandoffStatus.CONFIRMED,
                new AfterSalesTicketCreateResult("AS-123", "PENDING", "2026-07-13T00:00:00Z"),
                0,
                8
        );

        PendingHandoffAdminDetailResponse response = mapper.toDetail(record);
        JsonNode json = objectMapper.valueToTree(response);

        assertEquals(Set.of(
                "outboxId", "status", "handoffReasonCode", "failureCode", "summaryPreview",
                "productLineCode", "issueTypeCode", "priorityCode", "confirmedTicket",
                "attemptCount", "scheduledAt", "version", "createdAt", "updatedAt"
        ), fields(json));
        assertNull(response.scheduledAt(), "0 is an internal no-schedule sentinel");
        assertEquals("return", response.productLineCode());
        assertEquals("complaint", response.issueTypeCode());
        assertEquals("URGENT", response.priorityCode());
        assertEquals("AS-123", response.confirmedTicket().ticketId());
        assertEquals("[REDACTED] [REDACTED] [REDACTED]", response.summaryPreview());
        assertFalse(response.summaryPreview().contains("\n"));
        assertNoRecordSecrets(json.toString());
    }

    @Test
    void shouldMapAuditWithActorAndRedactedNoteButWithoutTenantOrOutboxCoordinates() {
        PendingHandoffReviewAuditEvent event = auditEvent();

        PendingHandoffAdminAuditEventResponse response = mapper.toAuditEvent(event);
        JsonNode json = objectMapper.valueToTree(response);

        assertEquals(Set.of(
                "auditId", "action", "actorId", "authorityUsed", "reasonCode",
                "decisionNote", "evidenceSource", "fromStatus", "toStatus",
                "fromVersion", "toVersion", "reviewFailureCode",
                "attemptCountAtDecision", "scheduledAt", "occurredAt"
        ), fields(json));
        assertEquals("admin-001", response.actorId());
        assertEquals("[REDACTED] [REDACTED] [REDACTED]", response.decisionNote());
        assertFalse(response.decisionNote().contains("\n"));
        assertFalse(json.has("tenantId"));
        assertFalse(json.has("outboxId"));
        assertFalse(json.toString().contains(TENANT_SECRET));
    }

    @Test
    void shouldMapAtomicDecisionThroughTheSameSafeDetailAndAuditProjections() {
        PendingHandoffRecord record = record(
                PendingHandoffStatus.CONFIRMED,
                new AfterSalesTicketCreateResult("AS-123", "PENDING", "2026-07-13T00:00:00Z"),
                0,
                8
        );
        PendingHandoffReviewAuditEvent event = auditEvent();

        PendingHandoffAdminDecisionResponse response = mapper.toDecision(
                new PendingHandoffManualDecisionResult(record, event)
        );
        JsonNode json = objectMapper.valueToTree(response);

        assertEquals(Set.of("record", "auditEvent"), fields(json));
        assertEquals(record.outboxId(), response.record().outboxId());
        assertEquals(event.auditId(), response.auditEvent().auditId());
        assertNull(response.record().scheduledAt());
        assertEquals("[REDACTED] [REDACTED] [REDACTED]", response.record().summaryPreview());
        assertEquals("[REDACTED] [REDACTED] [REDACTED]", response.auditEvent().decisionNote());
        assertNoRecordSecrets(json.toString());
    }

    @Test
    void shouldKeepRealRetryScheduleWhileOnlyHidingZeroAndMaxSentinels() {
        PendingHandoffRecord scheduled = record(
                PendingHandoffStatus.RETRY_SCHEDULED,
                null,
                1_726_000_000_999L,
                8
        );

        assertEquals(1_726_000_000_999L, mapper.toListItem(scheduled).scheduledAt());
        assertEquals(1_726_000_000_999L, mapper.toDetail(scheduled).scheduledAt());
    }

    private PendingHandoffRecord record(PendingHandoffStatus status,
                                        AfterSalesTicketCreateResult result,
                                        long nextAttemptAt,
                                        long version) {
        return new PendingHandoffRecord(
                "outbox-001",
                TENANT_SECRET,
                USER_SECRET,
                CONVERSATION_SECRET,
                "USER_REQUESTED_HUMAN",
                "buyer@example.com\n13800138000 token=secret",
                new AfterSalesTicketCreateRequest(
                        REQUEST_TITLE_SECRET,
                        REQUEST_DESCRIPTION_SECRET,
                        CUSTOMER_SECRET,
                        "return",
                        "complaint",
                        "URGENT",
                        IDEMPOTENCY_SECRET
                ),
                status,
                "SocketTimeoutException",
                result,
                2,
                nextAttemptAt,
                version,
                1_726_000_000_000L,
                1_726_000_000_100L
        );
    }

    private PendingHandoffReviewAuditEvent auditEvent() {
        return new PendingHandoffReviewAuditEvent(
                "audit-001",
                TENANT_SECRET,
                "outbox-001",
                PendingHandoffManualAction.CONFIRM,
                "admin-001",
                PendingHandoffReviewAuthority.ADMIN,
                "REMOTE_TICKET_VERIFIED",
                "admin@example.com\n13800138000 token=secret",
                PendingHandoffEvidenceSource.ADMIN_PROVIDED,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                PendingHandoffStatus.CONFIRMED,
                7,
                8,
                "SocketTimeoutException",
                2,
                null,
                1_726_000_000_200L
        );
    }

    private Set<String> fields(JsonNode object) {
        Set<String> fields = new LinkedHashSet<>();
        Iterator<String> names = object.fieldNames();
        names.forEachRemaining(fields::add);
        return fields;
    }

    private void assertNoRecordSecrets(String json) {
        assertFalse(json.contains(TENANT_SECRET));
        assertFalse(json.contains(USER_SECRET));
        assertFalse(json.contains(CONVERSATION_SECRET));
        assertFalse(json.contains(IDEMPOTENCY_SECRET));
        assertFalse(json.contains(REQUEST_TITLE_SECRET));
        assertFalse(json.contains(REQUEST_DESCRIPTION_SECRET));
        assertFalse(json.contains(CUSTOMER_SECRET));
        assertFalse(json.contains("buyer@example.com"));
        assertFalse(json.contains("13800138000"));
        assertFalse(json.contains("token=secret"));
    }
}
