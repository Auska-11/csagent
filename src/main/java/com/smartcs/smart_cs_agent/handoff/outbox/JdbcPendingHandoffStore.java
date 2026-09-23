package com.smartcs.smart_cs_agent.handoff.outbox;

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
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.PersistenceTransactionExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.PendingHandoffMapper;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffAuditRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffClaimRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffKeyRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffResultRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.PendingHandoffRow;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 基于 MyBatis 的 MySQL 人工转接 Outbox。
 *
 * <p>表结构、CAS、租约、结果证据和审计语义保持不变。需要原子性的操作通过 Spring
 * TransactionTemplate 执行；同一线程内的所有 Mapper 调用由 MyBatis-Spring 绑定到同一连接。</p>
 */
@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
public class JdbcPendingHandoffStore implements PendingHandoffStore,
        PendingHandoffReviewStore,
        PendingHandoffManualDecisionStore,
        PendingHandoffReviewAuditStore {

    private static final Set<PendingHandoffStatus> DUE_STATUSES = Set.of(
            PendingHandoffStatus.PREPARED,
            PendingHandoffStatus.RESULT_UNKNOWN,
            PendingHandoffStatus.RETRY_SCHEDULED,
            PendingHandoffStatus.PROCESSING
    );

    private final PendingHandoffMapper mapper;
    private final MyBatisMapperExecutor mapperExecutor;
    private final PersistenceTransactionExecutor transactionExecutor;
    private final long preparedGraceMs;

    public JdbcPendingHandoffStore(
            PendingHandoffMapper mapper,
            MyBatisMapperExecutor mapperExecutor,
            PersistenceTransactionExecutor transactionExecutor,
            @Value("${smartcs.handoff.recovery.prepared-grace-ms:30000}")
            long preparedGraceMs) {
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
        this.mapperExecutor = Objects.requireNonNull(
                mapperExecutor,
                "mapperExecutor cannot be null"
        );
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor,
                "transactionExecutor cannot be null"
        );
        if (preparedGraceMs <= 0) {
            throw new IllegalArgumentException("preparedGraceMs must be positive");
        }
        this.preparedGraceMs = preparedGraceMs;
        initTables();
    }

    private void initTables() {
        dbRun(mapper::createPendingTable);
        dbRun(mapper::createResultTable);
        dbRun(mapper::createClaimTable);
        dbRun(mapper::createAuditTable);
    }

    @Override
    public PendingHandoffPreparation prepare(PendingHandoffCommand command) {
        Objects.requireNonNull(command, "command cannot be null");
        String fingerprint = PendingHandoffFingerprint.of(command);
        long now = System.currentTimeMillis();
        String outboxId = UUID.randomUUID().toString();
        try {
            int changed = db(() -> mapper.insertPending(
                    outboxId,
                    fingerprint,
                    command,
                    PendingHandoffStatus.PREPARED.name(),
                    "NONE",
                    safeAdd(now, preparedGraceMs),
                    now
            ));
            if (changed != 1) {
                throw new IllegalStateException(
                        "Pending handoff insert did not change exactly one row"
                );
            }
        } catch (JdbcDuplicateKeyException duplicateKey) {
            StoredPendingHandoff existing = loadStored(command.key())
                    .orElseThrow(() -> duplicateKey);
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new PendingHandoffConflictException();
            }
            return new PendingHandoffPreparation(existing.record(), false);
        }

        PendingHandoffRecord inserted = findByKey(command.key())
                .orElseThrow(() -> new IllegalStateException(
                        "Inserted pending handoff could not be loaded"
                ));
        return new PendingHandoffPreparation(inserted, true);
    }

    @Override
    public PendingHandoffRecord markResultUnknown(PendingHandoffKey key,
                                                  String failureType,
                                                  long expectedVersion) {
        Objects.requireNonNull(key, "key cannot be null");
        long now = System.currentTimeMillis();
        int changed = db(() -> mapper.markResultUnknown(
                key.tenantId(),
                key.idempotencyKey(),
                safeFailureType(failureType),
                expectedVersion,
                now
        ));
        if (changed == 1) {
            return findByKey(key)
                    .orElseThrow(() -> new IllegalStateException(
                            "Updated pending handoff could not be loaded"
                    ));
        }
        PendingHandoffRecord current = findByKey(key)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Pending handoff record does not exist"
                ));
        if (current.status() == PendingHandoffStatus.CONFIRMED) {
            return current;
        }
        if (current.version() == expectedVersion
                && current.status() != PendingHandoffStatus.PREPARED
                && current.status() != PendingHandoffStatus.RESULT_UNKNOWN) {
            throw new PendingHandoffStateConflictException(current.status());
        }
        throw new PendingHandoffVersionConflictException();
    }

    @Override
    public PendingHandoffRecord confirm(PendingHandoffKey key,
                                        AfterSalesTicketCreateResult result,
                                        long expectedVersion) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(result, "result cannot be null");

        PendingHandoffRecord locked = transactionExecutor.required(() -> {
            PendingHandoffRecord current = findByKeyForUpdate(key)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Pending handoff record does not exist"
                    ));
            if (current.status() == PendingHandoffStatus.CONFIRMED) {
                ensureSameResult(current.confirmedResult(), result);
                return current;
            }
            if (current.status() == PendingHandoffStatus.MANUALLY_CLOSED) {
                throw new PendingHandoffStateConflictException(current.status());
            }
            if (current.version() != expectedVersion) {
                throw new PendingHandoffVersionConflictException();
            }
            saveConfirmedResult(key, result);
            return current;
        });
        if (locked.status() == PendingHandoffStatus.CONFIRMED) {
            return locked;
        }

        /*
         * 结果证据已在上面的事务中先落库。主表只是可恢复投影；即使投影前进程退出，
         * loadStored 仍会从追加式结果表恢复 CONFIRMED，因而不会再次调用外部系统。
         */
        int changed = db(() -> mapper.projectConfirmed(
                key.tenantId(),
                key.idempotencyKey(),
                System.currentTimeMillis()
        ));
        PendingHandoffRecord confirmed = findByKey(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Confirmed pending handoff could not be loaded"
                ));
        if (confirmed.status() == PendingHandoffStatus.MANUALLY_CLOSED) {
            throw new PendingHandoffStateConflictException(confirmed.status());
        }
        ensureSameResult(confirmed.confirmedResult(), result);
        if (changed == 0 && confirmed.status() != PendingHandoffStatus.CONFIRMED) {
            throw new PendingHandoffVersionConflictException();
        }
        return confirmed;
    }

    @Override
    public List<PendingHandoffRecord> findDue(long now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return db(() -> mapper.findDueKeys(now, limit)).stream()
                .map(this::toKey)
                .map(this::findByKey)
                .flatMap(Optional::stream)
                .filter(record -> record.status() != PendingHandoffStatus.CONFIRMED)
                .toList();
    }

    @Override
    public Optional<PendingHandoffClaim> tryClaim(PendingHandoffKey key,
                                                  long expectedVersion,
                                                  String ownerToken,
                                                  long now,
                                                  long leaseMs) {
        Objects.requireNonNull(key, "key cannot be null");
        requireText(ownerToken, "ownerToken");
        if (leaseMs <= 0) {
            throw new IllegalArgumentException("leaseMs must be positive");
        }
        PendingHandoffRecord current = findByKey(key).orElse(null);
        if (current == null
                || current.version() != expectedVersion
                || !DUE_STATUSES.contains(current.status())
                || current.nextAttemptAt() > now) {
            return Optional.empty();
        }

        long leaseUntil = safeAdd(now, leaseMs);
        if (!acquireClaimRow(key, ownerToken, now, leaseUntil)) {
            return Optional.empty();
        }
        int changed = db(() -> mapper.markProcessing(
                key.tenantId(),
                key.idempotencyKey(),
                expectedVersion,
                leaseUntil,
                now
        ));
        if (changed != 1) {
            releaseClaimRow(key, ownerToken);
            return Optional.empty();
        }
        PendingHandoffRecord processing = findByKey(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Claimed pending handoff could not be loaded"
                ));
        return Optional.of(new PendingHandoffClaim(processing, ownerToken, leaseUntil));
    }

    @Override
    public PendingHandoffRecord scheduleRetry(PendingHandoffClaim claim,
                                              String failureType,
                                              long nextAttemptAt) {
        return finishClaim(
                claim,
                PendingHandoffStatus.RETRY_SCHEDULED,
                failureType,
                nextAttemptAt
        );
    }

    @Override
    public PendingHandoffRecord markNeedsManualReview(PendingHandoffClaim claim,
                                                      String failureType) {
        return finishClaim(
                claim,
                PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                failureType,
                Long.MAX_VALUE
        );
    }

    @Override
    public PendingHandoffRecord markNeedsManualReviewByKey(PendingHandoffKey key,
                                                           String failureType) {
        Objects.requireNonNull(key, "key cannot be null");
        db(() -> mapper.markNeedsManualReviewByKey(
                key.tenantId(),
                key.idempotencyKey(),
                safeFailureType(failureType),
                Long.MAX_VALUE,
                System.currentTimeMillis()
        ));
        return loadStored(key)
                .map(StoredPendingHandoff::record)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Pending handoff record does not exist"
                ));
    }

    @Override
    public void releaseClaim(PendingHandoffClaim claim) {
        if (claim != null) {
            releaseClaimRow(claim.record().key(), claim.ownerToken());
        }
    }

    @Override
    public Optional<PendingHandoffRecord> findByKey(PendingHandoffKey key) {
        Objects.requireNonNull(key, "key cannot be null");
        return loadStored(key).map(StoredPendingHandoff::record);
    }

    @Override
    public PendingHandoffReviewPage findReviewPage(String tenantId,
                                                   PendingHandoffStatus status,
                                                   PendingHandoffReviewCursor cursor,
                                                   int limit) {
        requireText(tenantId, "tenantId");
        Objects.requireNonNull(status, "status cannot be null");
        requireReviewLimit(limit);
        String normalizedTenant = tenantId.trim();
        return transactionExecutor.required(() -> {
            int fetchLimit = limit + 1;
            List<PendingHandoffRecord> candidates = db(() -> mapper.findReviewKeys(
                            normalizedTenant,
                            status.name(),
                            cursor == null ? null : cursor.updatedAt(),
                            cursor == null ? null : cursor.outboxId(),
                            fetchLimit
                    )).stream()
                    .map(this::toKey)
                    .map(key -> loadStored(key).map(StoredPendingHandoff::record))
                    .flatMap(Optional::stream)
                    .toList();
            boolean hasMore = candidates.size() > limit;
            List<PendingHandoffRecord> pageRecords = hasMore
                    ? List.copyOf(candidates.subList(0, limit))
                    : candidates;
            PendingHandoffReviewCursor nextCursor = hasMore
                    ? cursorOf(pageRecords.get(pageRecords.size() - 1))
                    : null;
            return new PendingHandoffReviewPage(pageRecords, nextCursor, hasMore);
        });
    }

    @Override
    public Optional<PendingHandoffRecord> findByTenantAndOutboxId(
            String tenantId,
            String outboxId) {
        requireText(tenantId, "tenantId");
        requireText(outboxId, "outboxId");
        PendingHandoffKeyRow row = db(() -> mapper.findKeyByOutboxId(
                tenantId.trim(),
                outboxId.trim()
        ));
        return row == null ? Optional.empty() : findByKey(toKey(row));
    }

    @Override
    public PendingHandoffReviewAuditPage findAuditPage(
            String tenantId,
            String outboxId,
            PendingHandoffReviewAuditCursor cursor,
            int limit) {
        requireText(tenantId, "tenantId");
        requireText(outboxId, "outboxId");
        requireReviewLimit(limit);
        int fetchLimit = limit + 1;
        List<PendingHandoffReviewAuditEvent> candidates = db(() -> mapper.findAuditPage(
                        tenantId.trim(),
                        outboxId.trim(),
                        cursor == null ? null : cursor.occurredAt(),
                        cursor == null ? null : cursor.auditId(),
                        fetchLimit
                )).stream()
                .map(this::toAuditEvent)
                .toList();
        boolean hasMore = candidates.size() > limit;
        List<PendingHandoffReviewAuditEvent> pageEvents = hasMore
                ? List.copyOf(candidates.subList(0, limit))
                : candidates;
        PendingHandoffReviewAuditCursor nextCursor = hasMore
                ? auditCursorOf(pageEvents.get(pageEvents.size() - 1))
                : null;
        return new PendingHandoffReviewAuditPage(pageEvents, nextCursor, hasMore);
    }

    @Override
    public PendingHandoffManualDecisionResult retry(
            PendingHandoffManualDecisionContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        return applyManualDecision(
                context,
                PendingHandoffManualAction.RETRY,
                PendingHandoffStatus.RETRY_SCHEDULED,
                "MANUAL_RETRY_REQUESTED",
                null
        );
    }

    @Override
    public PendingHandoffManualDecisionResult confirm(
            PendingHandoffManualDecisionContext context,
            AfterSalesTicketCreateResult result) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(result, "result cannot be null");
        return applyManualDecision(
                context,
                PendingHandoffManualAction.CONFIRM,
                PendingHandoffStatus.CONFIRMED,
                "NONE",
                result
        );
    }

    @Override
    public PendingHandoffManualDecisionResult close(
            PendingHandoffManualDecisionContext context) {
        Objects.requireNonNull(context, "context cannot be null");
        return applyManualDecision(
                context,
                PendingHandoffManualAction.CLOSE,
                PendingHandoffStatus.MANUALLY_CLOSED,
                "MANUALLY_CLOSED",
                null
        );
    }

    private Optional<StoredPendingHandoff> loadStored(PendingHandoffKey key) {
        PendingHandoffRow row = db(() -> mapper.findPending(
                key.tenantId(),
                key.idempotencyKey()
        ));
        if (row == null) {
            return Optional.empty();
        }
        AfterSalesTicketCreateRequest request = new AfterSalesTicketCreateRequest(
                row.requestTitle(),
                row.requestDescription(),
                row.customerId(),
                row.productLine(),
                row.issueType(),
                row.priorityCode(),
                row.idempotencyKey()
        );
        PendingHandoffRecord current = new PendingHandoffRecord(
                row.outboxId(),
                row.tenantId(),
                row.userId(),
                row.conversationId(),
                row.reason(),
                row.summaryText(),
                request,
                PendingHandoffStatus.valueOf(row.status()),
                row.failureType(),
                null,
                row.attemptCount(),
                row.nextAttemptAt(),
                row.version(),
                row.createdAt(),
                row.updatedAt()
        );
        StoredPendingHandoff stored = new StoredPendingHandoff(
                row.requestFingerprintHash(),
                current
        );
        if (current.status() == PendingHandoffStatus.MANUALLY_CLOSED) {
            return Optional.of(stored);
        }
        Optional<AfterSalesTicketCreateResult> confirmedResult = loadConfirmedResult(key);
        if (confirmedResult.isEmpty()) {
            return Optional.of(stored);
        }
        PendingHandoffRecord confirmed = new PendingHandoffRecord(
                current.outboxId(),
                current.tenantId(),
                current.userId(),
                current.conversationId(),
                current.reason(),
                current.summary(),
                current.request(),
                PendingHandoffStatus.CONFIRMED,
                "NONE",
                confirmedResult.get(),
                current.attemptCount(),
                0,
                current.version(),
                current.createdAt(),
                current.updatedAt()
        );
        return Optional.of(new StoredPendingHandoff(stored.fingerprint(), confirmed));
    }

    private void saveConfirmedResult(PendingHandoffKey key,
                                     AfterSalesTicketCreateResult result) {
        try {
            db(() -> mapper.insertResult(
                    key.tenantId(),
                    key.idempotencyKey(),
                    result,
                    System.currentTimeMillis()
            ));
        } catch (JdbcDuplicateKeyException duplicateKey) {
            AfterSalesTicketCreateResult existing = loadConfirmedResult(key)
                    .orElseThrow(() -> duplicateKey);
            ensureSameResult(existing, result);
        }
    }

    private Optional<AfterSalesTicketCreateResult> loadConfirmedResult(
            PendingHandoffKey key) {
        PendingHandoffResultRow row = db(() -> mapper.findResult(
                key.tenantId(),
                key.idempotencyKey()
        ));
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new AfterSalesTicketCreateResult(
                row.ticketId(),
                row.ticketStatus(),
                row.ticketCreatedAt()
        ));
    }

    private boolean acquireClaimRow(PendingHandoffKey key,
                                    String ownerToken,
                                    long now,
                                    long leaseUntil) {
        try {
            return db(() -> mapper.insertClaim(
                    key.tenantId(),
                    key.idempotencyKey(),
                    ownerToken,
                    leaseUntil,
                    now
            )) == 1;
        } catch (JdbcDuplicateKeyException duplicateKey) {
            Optional<PendingHandoffClaimRow> existing = loadClaim(key);
            if (existing.isEmpty() || existing.get().leaseUntil() > now) {
                return false;
            }
            PendingHandoffClaimRow expired = existing.get();
            return db(() -> mapper.takeOverClaim(
                    key.tenantId(),
                    key.idempotencyKey(),
                    expired.ownerToken(),
                    expired.leaseUntil(),
                    ownerToken,
                    leaseUntil,
                    now
            )) == 1;
        }
    }

    private PendingHandoffRecord finishClaim(PendingHandoffClaim claim,
                                             PendingHandoffStatus targetStatus,
                                             String failureType,
                                             long nextAttemptAt) {
        Objects.requireNonNull(claim, "claim cannot be null");
        PendingHandoffKey key = claim.record().key();
        PendingHandoffClaimRow storedClaim = loadClaim(key)
                .orElseThrow(PendingHandoffVersionConflictException::new);
        long now = System.currentTimeMillis();
        if (!storedClaim.ownerToken().equals(claim.ownerToken())
                || storedClaim.leaseUntil() != claim.leaseUntil()
                || storedClaim.leaseUntil() <= now) {
            throw new PendingHandoffVersionConflictException();
        }
        try {
            int changed = db(() -> mapper.finishClaim(
                    key.tenantId(),
                    key.idempotencyKey(),
                    claim.record().version(),
                    claim.ownerToken(),
                    claim.leaseUntil(),
                    targetStatus.name(),
                    safeFailureType(failureType),
                    nextAttemptAt,
                    now
            ));
            if (changed != 1) {
                throw new PendingHandoffVersionConflictException();
            }
            return findByKey(key)
                    .orElseThrow(() -> new IllegalStateException(
                            "Finished pending handoff could not be loaded"
                    ));
        } finally {
            releaseClaimRow(key, claim.ownerToken());
        }
    }

    private Optional<PendingHandoffClaimRow> loadClaim(PendingHandoffKey key) {
        return Optional.ofNullable(db(() -> mapper.findClaim(
                key.tenantId(),
                key.idempotencyKey()
        )));
    }

    private void releaseClaimRow(PendingHandoffKey key, String ownerToken) {
        db(() -> mapper.deleteClaim(
                key.tenantId(),
                key.idempotencyKey(),
                ownerToken
        ));
    }

    private Optional<PendingHandoffRecord> findByKeyForUpdate(PendingHandoffKey key) {
        PendingHandoffKeyRow row = db(() -> mapper.findKeyForUpdate(
                key.tenantId(),
                key.idempotencyKey()
        ));
        return row == null ? Optional.empty() : findByKey(toKey(row));
    }

    private Optional<PendingHandoffRecord> findByTenantAndOutboxIdForUpdate(
            String tenantId,
            String outboxId) {
        PendingHandoffKeyRow row = db(() -> mapper.findKeyByOutboxIdForUpdate(
                tenantId,
                outboxId
        ));
        return row == null ? Optional.empty() : findByKey(toKey(row));
    }

    private PendingHandoffManualDecisionResult applyManualDecision(
            PendingHandoffManualDecisionContext context,
            PendingHandoffManualAction action,
            PendingHandoffStatus targetStatus,
            String targetFailureType,
            AfterSalesTicketCreateResult confirmedResult) {
        if ((action == PendingHandoffManualAction.CONFIRM) != (confirmedResult != null)) {
            throw new IllegalArgumentException(
                    "Only CONFIRM decisions may carry a confirmed result"
            );
        }
        return transactionExecutor.required(() -> {
            PendingHandoffRecord current = findByTenantAndOutboxIdForUpdate(
                    context.tenantId(),
                    context.outboxId()
            ).orElseThrow(PendingHandoffReviewNotFoundException::new);
            validateManualReviewTransition(current, context.expectedVersion());

            long occurredAt = System.currentTimeMillis();
            long nextAttemptAt = switch (action) {
                case RETRY -> occurredAt;
                case CONFIRM -> 0;
                case CLOSE -> Long.MAX_VALUE;
            };
            Long scheduledAt = action == PendingHandoffManualAction.RETRY ? occurredAt : null;
            PendingHandoffReviewAuditEvent auditEvent = new PendingHandoffReviewAuditEvent(
                    UUID.randomUUID().toString(),
                    current.tenantId(),
                    current.outboxId(),
                    action,
                    context.actorId(),
                    context.authorityUsed(),
                    context.reasonCode(),
                    context.decisionNote(),
                    context.evidenceSource(),
                    current.status(),
                    targetStatus,
                    current.version(),
                    current.version() + 1,
                    current.failureType(),
                    current.attemptCount(),
                    scheduledAt,
                    occurredAt
            );

            if (confirmedResult != null) {
                saveConfirmedResult(current.key(), confirmedResult);
            }
            int changed = db(() -> mapper.updateManualDecision(
                    context.tenantId(),
                    context.outboxId(),
                    context.expectedVersion(),
                    targetStatus.name(),
                    targetFailureType,
                    nextAttemptAt,
                    occurredAt
            ));
            if (changed != 1) {
                throw new PendingHandoffVersionConflictException();
            }
            appendAuditEvent(auditEvent);
            PendingHandoffRecord updated = loadStored(current.key())
                    .map(StoredPendingHandoff::record)
                    .orElseThrow(() -> new IllegalStateException(
                            "Audited manual handoff could not be loaded"
                    ));
            return new PendingHandoffManualDecisionResult(updated, auditEvent);
        });
    }

    private void appendAuditEvent(PendingHandoffReviewAuditEvent event) {
        int changed = db(() -> mapper.insertAudit(event));
        if (changed != 1) {
            throw new IllegalStateException(
                    "Pending handoff review audit insert did not change exactly one row"
            );
        }
    }

    private PendingHandoffReviewAuditEvent toAuditEvent(PendingHandoffAuditRow row) {
        return new PendingHandoffReviewAuditEvent(
                row.auditId(),
                row.tenantId(),
                row.outboxId(),
                PendingHandoffManualAction.valueOf(row.action()),
                row.actorId(),
                PendingHandoffReviewAuthority.valueOf(row.authorityUsed()),
                row.reasonCode(),
                row.decisionNote(),
                PendingHandoffEvidenceSource.valueOf(row.evidenceSource()),
                PendingHandoffStatus.valueOf(row.fromStatus()),
                PendingHandoffStatus.valueOf(row.toStatus()),
                row.fromVersion(),
                row.toVersion(),
                row.reviewFailureType(),
                row.attemptCountAtDecision(),
                row.scheduledAt(),
                row.occurredAt()
        );
    }

    private void validateManualReviewTransition(PendingHandoffRecord current,
                                                long expectedVersion) {
        if (current.version() != expectedVersion) {
            throw new PendingHandoffVersionConflictException();
        }
        if (current.status() != PendingHandoffStatus.NEEDS_MANUAL_REVIEW) {
            throw new PendingHandoffStateConflictException(current.status());
        }
    }

    private PendingHandoffKey toKey(PendingHandoffKeyRow row) {
        return new PendingHandoffKey(row.tenantId(), row.idempotencyKey());
    }

    private PendingHandoffReviewCursor cursorOf(PendingHandoffRecord record) {
        return new PendingHandoffReviewCursor(record.updatedAt(), record.outboxId());
    }

    private PendingHandoffReviewAuditCursor auditCursorOf(
            PendingHandoffReviewAuditEvent event) {
        return new PendingHandoffReviewAuditCursor(event.occurredAt(), event.auditId());
    }

    private void requireReviewLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private void ensureSameResult(AfterSalesTicketCreateResult existing,
                                  AfterSalesTicketCreateResult candidate) {
        if (existing == null || !existing.equals(candidate)) {
            throw new PendingHandoffConflictException();
        }
    }

    private String safeFailureType(String failureType) {
        if (failureType == null || failureType.isBlank()) {
            return "UNKNOWN";
        }
        String compact = failureType.replaceAll("[^A-Za-z0-9_$.-]", "_");
        return compact.length() <= 128 ? compact : compact.substring(0, 128);
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }

    private long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private <T> T db(Supplier<T> operation) {
        return mapperExecutor.execute(operation);
    }

    private void dbRun(Runnable operation) {
        mapperExecutor.run(operation);
    }

    private record StoredPendingHandoff(String fingerprint, PendingHandoffRecord record) {
    }
}
