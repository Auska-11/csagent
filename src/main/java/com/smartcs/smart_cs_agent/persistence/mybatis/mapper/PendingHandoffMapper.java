package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffCommand;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditEvent;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffAuditRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffClaimRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffKeyRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffResultRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffRow;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PendingHandoffMapper {

    void createPendingTable();

    void createResultTable();

    void createClaimTable();

    void createAuditTable();

    int insertPending(
            @Param("outboxId") String outboxId,
            @Param("fingerprint") String fingerprint,
            @Param("command") PendingHandoffCommand command,
            @Param("status") String status,
            @Param("failureType") String failureType,
            @Param("nextAttemptAt") long nextAttemptAt,
            @Param("now") long now);

    int markResultUnknown(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("failureType") String failureType,
            @Param("expectedVersion") long expectedVersion,
            @Param("now") long now);

    int projectConfirmed(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("now") long now);

    List<PendingHandoffKeyRow> findDueKeys(
            @Param("now") long now,
            @Param("limit") int limit);

    int markProcessing(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("expectedVersion") long expectedVersion,
            @Param("leaseUntil") long leaseUntil,
            @Param("now") long now);

    int finishClaim(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("expectedVersion") long expectedVersion,
            @Param("ownerToken") String ownerToken,
            @Param("leaseUntil") long leaseUntil,
            @Param("targetStatus") String targetStatus,
            @Param("failureType") String failureType,
            @Param("nextAttemptAt") long nextAttemptAt,
            @Param("now") long now);

    PendingHandoffRow findPending(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey);

    PendingHandoffKeyRow findKeyByOutboxId(
            @Param("tenantId") String tenantId,
            @Param("outboxId") String outboxId);

    PendingHandoffKeyRow findKeyByOutboxIdForUpdate(
            @Param("tenantId") String tenantId,
            @Param("outboxId") String outboxId);

    PendingHandoffKeyRow findKeyForUpdate(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey);

    List<PendingHandoffKeyRow> findReviewKeys(
            @Param("tenantId") String tenantId,
            @Param("status") String status,
            @Param("cursorUpdatedAt") Long cursorUpdatedAt,
            @Param("cursorOutboxId") String cursorOutboxId,
            @Param("limit") int limit);

    int insertResult(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("result") AfterSalesTicketCreateResult result,
            @Param("recordedAt") long recordedAt);

    PendingHandoffResultRow findResult(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey);

    int insertClaim(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("ownerToken") String ownerToken,
            @Param("leaseUntil") long leaseUntil,
            @Param("now") long now);

    PendingHandoffClaimRow findClaim(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey);

    int takeOverClaim(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("oldOwnerToken") String oldOwnerToken,
            @Param("oldLeaseUntil") long oldLeaseUntil,
            @Param("newOwnerToken") String newOwnerToken,
            @Param("newLeaseUntil") long newLeaseUntil,
            @Param("now") long now);

    int deleteClaim(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("ownerToken") String ownerToken);

    int updateManualDecision(
            @Param("tenantId") String tenantId,
            @Param("outboxId") String outboxId,
            @Param("expectedVersion") long expectedVersion,
            @Param("targetStatus") String targetStatus,
            @Param("failureType") String failureType,
            @Param("nextAttemptAt") long nextAttemptAt,
            @Param("now") long now);

    int insertAudit(@Param("event") PendingHandoffReviewAuditEvent event);

    List<PendingHandoffAuditRow> findAuditPage(
            @Param("tenantId") String tenantId,
            @Param("outboxId") String outboxId,
            @Param("cursorOccurredAt") Long cursorOccurredAt,
            @Param("cursorAuditId") String cursorAuditId,
            @Param("limit") int limit);
}
