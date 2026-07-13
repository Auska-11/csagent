package com.smartcs.smart_cs_agent.handoff.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewCursor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingHandoffAdminCursorCodecTest {

    private static final String SAFE_ERROR_MESSAGE = "Pending handoff cursor is invalid";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PendingHandoffAdminCursorCodec codec = new PendingHandoffAdminCursorCodec(objectMapper);

    @Test
    void shouldRoundTripReviewCursorAndBindStatusInBase64UrlPayload() throws Exception {
        PendingHandoffReviewCursor source = new PendingHandoffReviewCursor(1_726_000_000_123L, "outbox-001");

        String encoded = codec.encodeReview(source, PendingHandoffStatus.NEEDS_MANUAL_REVIEW);
        PendingHandoffReviewCursor decoded = codec.decodeReview(
                encoded,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW
        );

        assertEquals(source, decoded);
        assertFalse(encoded.contains("="), "Base64URL cursor should omit padding");
        assertTrue(encoded.matches("[A-Za-z0-9_-]+"));

        JsonNode payload = decodePayload(encoded);
        assertEquals(1, payload.get("version").asInt());
        assertEquals("review", payload.get("kind").asText());
        assertEquals("NEEDS_MANUAL_REVIEW", payload.get("status").asText());
        assertTrue(payload.get("resourceOutboxId").isNull());
        assertEquals(source.updatedAt(), payload.get("sortTime").asLong());
        assertEquals(source.outboxId(), payload.get("sortId").asText());
    }

    @Test
    void shouldRoundTripAuditCursorAndBindResourceOutboxInPayload() throws Exception {
        PendingHandoffReviewAuditCursor source = new PendingHandoffReviewAuditCursor(
                1_726_000_000_456L,
                "audit-001"
        );

        String encoded = codec.encodeAudit(source, "outbox-001");
        PendingHandoffReviewAuditCursor decoded = codec.decodeAudit(encoded, "outbox-001");

        assertEquals(source, decoded);
        JsonNode payload = decodePayload(encoded);
        assertEquals(1, payload.get("version").asInt());
        assertEquals("audit", payload.get("kind").asText());
        assertTrue(payload.get("status").isNull());
        assertEquals("outbox-001", payload.get("resourceOutboxId").asText());
        assertEquals(source.occurredAt(), payload.get("sortTime").asLong());
        assertEquals(source.auditId(), payload.get("sortId").asText());
    }

    @Test
    void shouldRejectReviewCursorCopiedToAnotherStatus() {
        String encoded = codec.encodeReview(
                new PendingHandoffReviewCursor(100, "outbox-001"),
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW
        );

        assertSafeCursorFailure(() -> codec.decodeReview(encoded, PendingHandoffStatus.CONFIRMED));
    }

    @Test
    void shouldRejectAuditCursorCopiedToAnotherOutbox() {
        String encoded = codec.encodeAudit(
                new PendingHandoffReviewAuditCursor(100, "audit-001"),
                "outbox-001"
        );

        assertSafeCursorFailure(() -> codec.decodeAudit(encoded, "outbox-002"));
    }

    @Test
    void shouldRejectCursorUsedByTheWrongEndpointKind() {
        String review = codec.encodeReview(
                new PendingHandoffReviewCursor(100, "outbox-001"),
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW
        );
        String audit = codec.encodeAudit(
                new PendingHandoffReviewAuditCursor(100, "audit-001"),
                "outbox-001"
        );

        assertSafeCursorFailure(() -> codec.decodeAudit(review, "outbox-001"));
        assertSafeCursorFailure(() -> codec.decodeReview(audit, PendingHandoffStatus.NEEDS_MANUAL_REVIEW));
    }

    @Test
    void shouldRejectMalformedBase64AndMalformedJsonWithoutEchoingInput() {
        String invalidBase64 = "attacker*cursor*token=secret";
        String invalidJson = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{not-json".getBytes(StandardCharsets.UTF_8));

        assertSafeCursorFailure(() -> codec.decodeReview(
                invalidBase64,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW
        ));
        assertSafeCursorFailure(() -> codec.decodeReview(
                invalidJson,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW
        ));
    }

    @Test
    void shouldRejectEncodedCursorLongerThanTransportLimit() {
        assertSafeCursorFailure(() -> codec.decodeReview(
                "A".repeat(513),
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW
        ));
    }

    @Test
    void shouldTreatMissingCursorAsTheFirstPage() {
        assertNull(codec.encodeReview(null, PendingHandoffStatus.NEEDS_MANUAL_REVIEW));
        assertNull(codec.encodeAudit(null, "outbox-001"));
        assertNull(codec.decodeReview(null, PendingHandoffStatus.NEEDS_MANUAL_REVIEW));
        assertNull(codec.decodeReview("   ", PendingHandoffStatus.NEEDS_MANUAL_REVIEW));
        assertNull(codec.decodeAudit(null, "outbox-001"));
        assertNull(codec.decodeAudit("   ", "outbox-001"));
    }

    private JsonNode decodePayload(String encoded) throws Exception {
        return objectMapper.readTree(Base64.getUrlDecoder().decode(encoded));
    }

    private void assertSafeCursorFailure(org.junit.jupiter.api.function.Executable executable) {
        PendingHandoffAdminCursorException exception = assertThrows(
                PendingHandoffAdminCursorException.class,
                executable
        );
        assertEquals(SAFE_ERROR_MESSAGE, exception.getMessage());
        assertNull(exception.getCause(), "HTTP-safe cursor exception should not retain parser details");
    }
}
