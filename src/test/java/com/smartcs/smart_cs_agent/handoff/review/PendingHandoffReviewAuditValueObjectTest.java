package com.smartcs.smart_cs_agent.handoff.review;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 20.3 人工决定与审计值对象的安全边界测试。
 *
 * <p>Adapter 测试负责证明事务和并发；这里先证明无论使用哪种 Adapter，领域对象都不会接受不可能的
 * 状态组合，也不会通过默认 toString 把管理员自由输入写入日志。</p>
 */
class PendingHandoffReviewAuditValueObjectTest {

    @Test
    void shouldNormalizeDecisionContextAndRedactBoundFreeText() {
        String longNote = "联系 test@example.com\n手机号 13800138000 " + "好".repeat(300);

        PendingHandoffManualDecisionContext context = new PendingHandoffManualDecisionContext(
                " tenant-a ",
                " outbox-1 ",
                7,
                " admin-1 ",
                PendingHandoffReviewAuthority.ADMIN,
                " remote result-verified ",
                longNote,
                PendingHandoffEvidenceSource.ADMIN_PROVIDED
        );

        assertEquals("tenant-a", context.tenantId());
        assertEquals("outbox-1", context.outboxId());
        assertEquals("admin-1", context.actorId());
        assertEquals("REMOTE_RESULT_VERIFIED", context.reasonCode());
        assertTrue(context.decisionNote().contains("[REDACTED]"));
        assertFalse(context.decisionNote().contains("test@example.com"));
        assertFalse(context.decisionNote().contains("13800138000"));
        assertFalse(context.decisionNote().contains("\n"));
        assertTrue(context.decisionNote().codePointCount(0, context.decisionNote().length()) <= 256);
    }

    @Test
    void shouldRejectInvalidDecisionContextCoordinatesAndReasonCode() {
        assertThrows(IllegalArgumentException.class, () -> context(" ", "OK"));
        assertThrows(IllegalArgumentException.class, () -> new PendingHandoffManualDecisionContext(
                "tenant-a",
                "outbox-1",
                -1,
                "admin-1",
                PendingHandoffReviewAuthority.ADMIN,
                "VALID_REASON",
                "note",
                PendingHandoffEvidenceSource.NONE
        ));
        assertThrows(IllegalArgumentException.class, () -> context("tenant-a", "bad.reason"));
        assertThrows(IllegalArgumentException.class, () -> context("tenant-a", "_BAD_REASON"));
        assertThrows(IllegalArgumentException.class, () -> context("tenant-a", "A".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> context("tenant-a", "ß".repeat(64)),
                "reasonCode 大写展开后仍必须满足数据库 64 字符边界");
    }

    @Test
    void shouldAcceptOnlyValidActionStateEvidenceAndScheduleCombinations() {
        // 三个合法分支先作为正例固定下来。
        auditEvent(
                PendingHandoffManualAction.RETRY,
                PendingHandoffStatus.RETRY_SCHEDULED,
                PendingHandoffEvidenceSource.NONE,
                2_000L
        );
        auditEvent(
                PendingHandoffManualAction.CONFIRM,
                PendingHandoffStatus.CONFIRMED,
                PendingHandoffEvidenceSource.ADMIN_PROVIDED,
                null
        );
        auditEvent(
                PendingHandoffManualAction.CONFIRM,
                PendingHandoffStatus.CONFIRMED,
                PendingHandoffEvidenceSource.AFTER_SALES_IDEMPOTENCY_QUERY,
                null
        );
        auditEvent(
                PendingHandoffManualAction.CLOSE,
                PendingHandoffStatus.MANUALLY_CLOSED,
                PendingHandoffEvidenceSource.NONE,
                null
        );

        assertThrows(IllegalArgumentException.class, () -> auditEvent(
                PendingHandoffManualAction.RETRY,
                PendingHandoffStatus.CONFIRMED,
                PendingHandoffEvidenceSource.NONE,
                2_000L
        ));
        assertThrows(IllegalArgumentException.class, () -> auditEvent(
                PendingHandoffManualAction.RETRY,
                PendingHandoffStatus.RETRY_SCHEDULED,
                PendingHandoffEvidenceSource.NONE,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> auditEvent(
                PendingHandoffManualAction.CONFIRM,
                PendingHandoffStatus.CONFIRMED,
                PendingHandoffEvidenceSource.NONE,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> auditEvent(
                PendingHandoffManualAction.CLOSE,
                PendingHandoffStatus.MANUALLY_CLOSED,
                PendingHandoffEvidenceSource.ADMIN_PROVIDED,
                null
        ));
    }

    @Test
    void shouldRejectInvalidSourceStatusVersionAndCounters() {
        assertThrows(IllegalArgumentException.class, () -> new PendingHandoffReviewAuditEvent(
                "audit-1", "tenant-a", "outbox-1", PendingHandoffManualAction.CLOSE,
                "admin-1", PendingHandoffReviewAuthority.ADMIN, "NO_ACTION", "note",
                PendingHandoffEvidenceSource.NONE, PendingHandoffStatus.PROCESSING,
                PendingHandoffStatus.MANUALLY_CLOSED, 7, 8, "Timeout", 2, null, 1_000
        ));
        assertThrows(IllegalArgumentException.class, () -> new PendingHandoffReviewAuditEvent(
                "audit-1", "tenant-a", "outbox-1", PendingHandoffManualAction.CLOSE,
                "admin-1", PendingHandoffReviewAuthority.ADMIN, "NO_ACTION", "note",
                PendingHandoffEvidenceSource.NONE, PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                PendingHandoffStatus.MANUALLY_CLOSED, 7, 9, "Timeout", 2, null, 1_000
        ));
        assertThrows(IllegalArgumentException.class, () -> new PendingHandoffReviewAuditEvent(
                "audit-1", "tenant-a", "outbox-1", PendingHandoffManualAction.CLOSE,
                "admin-1", PendingHandoffReviewAuthority.ADMIN, "NO_ACTION", "note",
                PendingHandoffEvidenceSource.NONE, PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                PendingHandoffStatus.MANUALLY_CLOSED, 7, 8, "Timeout", -1, null, 1_000
        ));
    }

    @Test
    void shouldDefensivelyCopyAuditPageAndValidateCursorContract() {
        PendingHandoffReviewAuditEvent event = auditEvent(
                PendingHandoffManualAction.CLOSE,
                PendingHandoffStatus.MANUALLY_CLOSED,
                PendingHandoffEvidenceSource.NONE,
                null
        );
        List<PendingHandoffReviewAuditEvent> mutable = new ArrayList<>();
        mutable.add(event);
        PendingHandoffReviewAuditCursor cursor = new PendingHandoffReviewAuditCursor(
                1_000,
                " audit-1 "
        );
        PendingHandoffReviewAuditPage page = new PendingHandoffReviewAuditPage(
                mutable,
                cursor,
                true
        );

        mutable.clear();
        assertEquals(1, page.events().size());
        assertEquals("audit-1", page.nextCursor().auditId());
        assertThrows(UnsupportedOperationException.class, () -> page.events().add(event));
        assertThrows(IllegalArgumentException.class,
                () -> new PendingHandoffReviewAuditPage(List.of(), null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new PendingHandoffReviewAuditPage(List.of(event), cursor, false));
    }

    @Test
    void shouldKeepSensitiveAuditFieldsOutOfToString() {
        PendingHandoffReviewAuditEvent event = new PendingHandoffReviewAuditEvent(
                "audit-visible",
                "tenant-secret",
                "outbox-secret",
                PendingHandoffManualAction.CLOSE,
                "actor-secret",
                PendingHandoffReviewAuthority.ADMIN,
                "NO_ACTION_REQUIRED",
                "private note",
                PendingHandoffEvidenceSource.NONE,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                PendingHandoffStatus.MANUALLY_CLOSED,
                7,
                8,
                "SocketTimeoutException",
                2,
                null,
                1_000
        );

        String text = event.toString();
        assertTrue(text.contains("audit-visible"));
        assertTrue(text.contains("CLOSE"));
        assertFalse(text.contains("tenant-secret"));
        assertFalse(text.contains("outbox-secret"));
        assertFalse(text.contains("actor-secret"));
        assertFalse(text.contains("private note"));
    }

    @Test
    void shouldKeepSensitiveContextAndRecordFieldsOutOfDecisionToString() {
        PendingHandoffManualDecisionContext context = new PendingHandoffManualDecisionContext(
                "tenant-secret",
                "outbox-secret",
                7,
                "actor-secret",
                PendingHandoffReviewAuthority.ADMIN,
                "REMOTE_VERIFIED",
                "private decision note",
                PendingHandoffEvidenceSource.ADMIN_PROVIDED
        );
        PendingHandoffReviewAuditEvent event = auditEvent(
                PendingHandoffManualAction.CONFIRM,
                PendingHandoffStatus.CONFIRMED,
                PendingHandoffEvidenceSource.ADMIN_PROVIDED,
                null
        );
        PendingHandoffRecord sensitiveRecord = record(
                "tenant-secret",
                "outbox-secret",
                PendingHandoffStatus.CONFIRMED,
                8
        );
        PendingHandoffManualDecisionResult result = new PendingHandoffManualDecisionResult(
                sensitiveRecord,
                event
        );

        String contextText = context.toString();
        assertTrue(contextText.contains("REMOTE_VERIFIED"));
        assertFalse(contextText.contains("tenant-secret"));
        assertFalse(contextText.contains("outbox-secret"));
        assertFalse(contextText.contains("actor-secret"));
        assertFalse(contextText.contains("private decision note"));

        String resultText = result.toString();
        assertTrue(resultText.contains("CONFIRMED"));
        assertFalse(resultText.contains("tenant-secret"));
        assertFalse(resultText.contains("outbox-secret"));
        assertFalse(resultText.contains("summary"));
    }

    @Test
    void shouldRequireDecisionResultToDescribeOneCommittedChange() {
        PendingHandoffReviewAuditEvent event = auditEvent(
                PendingHandoffManualAction.CLOSE,
                PendingHandoffStatus.MANUALLY_CLOSED,
                PendingHandoffEvidenceSource.NONE,
                null
        );
        PendingHandoffRecord matchingRecord = record(
                "tenant-secret",
                "outbox-secret",
                PendingHandoffStatus.MANUALLY_CLOSED,
                8
        );

        PendingHandoffManualDecisionResult result = new PendingHandoffManualDecisionResult(
                matchingRecord,
                event
        );
        assertEquals(matchingRecord, result.record());
        assertThrows(IllegalArgumentException.class, () -> new PendingHandoffManualDecisionResult(
                record("tenant-other", "outbox-secret", PendingHandoffStatus.MANUALLY_CLOSED, 8),
                event
        ));
    }

    private PendingHandoffManualDecisionContext context(String tenantId, String reasonCode) {
        return new PendingHandoffManualDecisionContext(
                tenantId,
                "outbox-1",
                7,
                "admin-1",
                PendingHandoffReviewAuthority.ADMIN,
                reasonCode,
                "note",
                PendingHandoffEvidenceSource.NONE
        );
    }

    private PendingHandoffReviewAuditEvent auditEvent(PendingHandoffManualAction action,
                                                       PendingHandoffStatus toStatus,
                                                       PendingHandoffEvidenceSource evidenceSource,
                                                       Long scheduledAt) {
        return new PendingHandoffReviewAuditEvent(
                "audit-visible",
                "tenant-secret",
                "outbox-secret",
                action,
                "actor-secret",
                PendingHandoffReviewAuthority.ADMIN,
                "decision recorded",
                "private note",
                evidenceSource,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                toStatus,
                7,
                8,
                "SocketTimeoutException",
                2,
                scheduledAt,
                1_000
        );
    }

    private PendingHandoffRecord record(String tenantId,
                                        String outboxId,
                                        PendingHandoffStatus status,
                                        long version) {
        return new PendingHandoffRecord(
                outboxId,
                tenantId,
                "user-1",
                "conversation-1",
                "reason",
                "summary",
                null,
                status,
                "SocketTimeoutException",
                null,
                2,
                Long.MAX_VALUE,
                version,
                100,
                1_000
        );
    }
}
