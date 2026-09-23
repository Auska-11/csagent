package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualAction;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionContext;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionResult;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffManualDecisionStore;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditEvent;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewAuditStore;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewCursor;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewNotFoundException;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewPage;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffReviewStore;
import com.smartcs.smart_cs_agent.handoff.review.PendingHandoffStateConflictException;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地演示和单元测试使用的 Outbox。
 *
 * <p>它能保证单 JVM 内并发去重，但不能跨进程恢复；生产 JDBC 模式会自动切换到数据库实现。</p>
 */
@Component
@ConditionalOnProperty(
        name = "smartcs.persistence.mode",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemoryPendingHandoffStore implements PendingHandoffStore,
        PendingHandoffReviewStore,
        PendingHandoffManualDecisionStore,
        PendingHandoffReviewAuditStore {

    /*
     * Map 的 key 必须同时包含 tenantId 和 idempotencyKey。若仍使用 String，租户 A 的成功结果或
     * 短租约可能被租户 B 的同名幂等请求误用。
     */
    private final Map<PendingHandoffKey, StoredEntry> records = new ConcurrentHashMap<>();
    private final Map<PendingHandoffKey, ClaimEntry> claims = new ConcurrentHashMap<>();
    private final long preparedGraceMs;
    private static final Set<PendingHandoffStatus> DUE_STATUSES = Set.of(
            PendingHandoffStatus.PREPARED,
            PendingHandoffStatus.RESULT_UNKNOWN,
            PendingHandoffStatus.RETRY_SCHEDULED,
            PendingHandoffStatus.PROCESSING
    );
    private static final Comparator<PendingHandoffRecord> REVIEW_ORDER =
            Comparator.comparingLong(PendingHandoffRecord::updatedAt)
                    .reversed()
                    .thenComparing(PendingHandoffRecord::outboxId, Comparator.reverseOrder());
    private static final Comparator<PendingHandoffReviewAuditEvent> AUDIT_ORDER =
            Comparator.comparingLong(PendingHandoffReviewAuditEvent::occurredAt)
                    .reversed()
                    .thenComparing(PendingHandoffReviewAuditEvent::auditId, Comparator.reverseOrder());

    /** 手动构造和单元测试使用与生产默认值一致的 30 秒宽限期。 */
    public InMemoryPendingHandoffStore() {
        this(30_000);
    }

    @Autowired
    public InMemoryPendingHandoffStore(
            @Value("${smartcs.handoff.recovery.prepared-grace-ms:30000}") long preparedGraceMs) {
        if (preparedGraceMs <= 0) {
            throw new IllegalArgumentException("preparedGraceMs must be positive");
        }
        this.preparedGraceMs = preparedGraceMs;
    }

    @Override
    public PendingHandoffPreparation prepare(PendingHandoffCommand command) {
        PendingHandoffKey key = command.key();
        String fingerprint = PendingHandoffFingerprint.of(command);
        StoredEntry candidate = new StoredEntry(fingerprint, newRecord(command), List.of());
        StoredEntry existing = records.putIfAbsent(key, candidate);
        if (existing == null) {
            return new PendingHandoffPreparation(candidate.record(), true);
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new PendingHandoffConflictException();
        }
        return new PendingHandoffPreparation(existing.record(), false);
    }

    @Override
    public PendingHandoffRecord markResultUnknown(PendingHandoffKey key,
                                                  String failureType,
                                                  long expectedVersion) {
        StoredEntry existing = requireEntry(key);
        if (existing.record().status() == PendingHandoffStatus.CONFIRMED) {
            // 成功是不可逆终态。迟到的超时线程不能把已确认结果改回未知。
            return existing.record();
        }
        if (existing.record().version() != expectedVersion) {
            // 与 JDBC 和人工状态机保持一致：先判断调用方看到的版本是否过期，再解释当前状态。
            // 旧页面不能利用状态异常差异推断一条已经被并发修改的记录。
            throw new PendingHandoffVersionConflictException();
        }
        if (existing.record().status() != PendingHandoffStatus.PREPARED
                && existing.record().status() != PendingHandoffStatus.RESULT_UNKNOWN) {
            throw new PendingHandoffStateConflictException(existing.record().status());
        }
        return replace(key, expectedVersion, current -> new PendingHandoffRecord(
                current.outboxId(),
                current.tenantId(),
                current.userId(),
                current.conversationId(),
                current.reason(),
                current.summary(),
                current.request(),
                PendingHandoffStatus.RESULT_UNKNOWN,
                safeFailureType(failureType),
                null,
                current.attemptCount() + 1,
                System.currentTimeMillis(),
                current.version() + 1,
                current.createdAt(),
                System.currentTimeMillis()
        ));
    }

    @Override
    public PendingHandoffRecord confirm(PendingHandoffKey key,
                                        AfterSalesTicketCreateResult result,
                                        long expectedVersion) {
        if (result == null) {
            throw new IllegalArgumentException("result cannot be null");
        }
        StoredEntry existing = requireEntry(key);
        if (existing.record().status() == PendingHandoffStatus.CONFIRMED) {
            if (!java.util.Objects.equals(existing.record().confirmedResult(), result)) {
                throw new PendingHandoffConflictException();
            }
            return existing.record();
        }
        if (existing.record().status() == PendingHandoffStatus.MANUALLY_CLOSED) {
            throw new PendingHandoffStateConflictException(existing.record().status());
        }
        return replace(key, expectedVersion, current -> new PendingHandoffRecord(
                current.outboxId(),
                current.tenantId(),
                current.userId(),
                current.conversationId(),
                current.reason(),
                current.summary(),
                current.request(),
                PendingHandoffStatus.CONFIRMED,
                "NONE",
                result,
                current.attemptCount() + 1,
                0,
                current.version() + 1,
                current.createdAt(),
                System.currentTimeMillis()
        ));
    }

    @Override
    public List<PendingHandoffRecord> findDue(long now, int limit) {
        requirePositive(limit, "limit");
        return records.values().stream()
                .map(StoredEntry::record)
                .filter(record -> DUE_STATUSES.contains(record.status()))
                .filter(record -> record.nextAttemptAt() <= now)
                .sorted(Comparator.comparingLong(PendingHandoffRecord::nextAttemptAt)
                        .thenComparing(PendingHandoffRecord::outboxId))
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<PendingHandoffClaim> tryClaim(PendingHandoffKey key,
                                                  long expectedVersion,
                                                  String ownerToken,
                                                  long now,
                                                  long leaseMs) {
        requireText(ownerToken, "ownerToken");
        if (leaseMs <= 0) {
            throw new IllegalArgumentException("leaseMs must be positive");
        }
        StoredEntry current = records.get(key);
        if (current == null) {
            // 与 JDBC 实现保持一致：扫描结果可能已经被其他实例删除或归档，领取时返回 empty 即可。
            return Optional.empty();
        }
        if (current.record().version() != expectedVersion
                || !DUE_STATUSES.contains(current.record().status())
                || current.record().nextAttemptAt() > now) {
            return Optional.empty();
        }

        long leaseUntil = now + leaseMs;
        AtomicBoolean acquired = new AtomicBoolean(false);
        claims.compute(key, (ignored, existing) -> {
            if (existing == null || existing.leaseUntil() <= now) {
                acquired.set(true);
                return new ClaimEntry(ownerToken, leaseUntil);
            }
            return existing;
        });
        if (!acquired.get()) {
            return Optional.empty();
        }

        try {
            PendingHandoffRecord processing = replace(
                    key,
                    expectedVersion,
                    record -> copyWithState(
                            record,
                            PendingHandoffStatus.PROCESSING,
                            record.failureType(),
                            record.confirmedResult(),
                            record.attemptCount(),
                            leaseUntil
                    )
            );
            return Optional.of(new PendingHandoffClaim(processing, ownerToken, leaseUntil));
        } catch (PendingHandoffVersionConflictException conflict) {
            claims.remove(key, new ClaimEntry(ownerToken, leaseUntil));
            return Optional.empty();
        }
    }

    @Override
    public PendingHandoffRecord scheduleRetry(PendingHandoffClaim claim,
                                              String failureType,
                                              long nextAttemptAt) {
        requireClaimOwner(claim);
        try {
            return replace(
                    claim.record().key(),
                    claim.record().version(),
                    record -> copyWithState(
                            record,
                            PendingHandoffStatus.RETRY_SCHEDULED,
                            safeFailureType(failureType),
                            null,
                            record.attemptCount() + 1,
                            nextAttemptAt
                    )
            );
        } finally {
            releaseClaim(claim);
        }
    }

    @Override
    public PendingHandoffRecord markNeedsManualReview(PendingHandoffClaim claim,
                                                      String failureType) {
        requireClaimOwner(claim);
        try {
            return replace(
                    claim.record().key(),
                    claim.record().version(),
                    record -> copyWithState(
                            record,
                            PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                            safeFailureType(failureType),
                            null,
                            record.attemptCount() + 1,
                            Long.MAX_VALUE
                    )
            );
        } finally {
            releaseClaim(claim);
        }
    }

    @Override
    public PendingHandoffRecord markNeedsManualReviewByKey(PendingHandoffKey key,
                                                           String failureType) {
        Objects.requireNonNull(key, "key cannot be null");
        AtomicReference<PendingHandoffRecord> outcome = new AtomicReference<>();
        // compute 保证“读状态→判终态→写复核”相对并发恢复线程原子；调用方没有租约，
        // 因此不能走 replace(expectedVersion)，只能收敛到最新版本。
        records.compute(key, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Pending handoff record does not exist");
            }
            PendingHandoffRecord current = existing.record();
            if (current.status() == PendingHandoffStatus.CONFIRMED
                    || current.status() == PendingHandoffStatus.MANUALLY_CLOSED) {
                // 成功与人工关闭都是终态：迟到的拒绝分类不能把它们拉回复核。
                outcome.set(current);
                return existing;
            }
            PendingHandoffRecord updated = copyWithState(
                    current,
                    PendingHandoffStatus.NEEDS_MANUAL_REVIEW,
                    safeFailureType(failureType),
                    null,
                    current.attemptCount() + 1,
                    Long.MAX_VALUE
            );
            outcome.set(updated);
            return new StoredEntry(existing.fingerprint(), updated, existing.auditHistory());
        });
        return outcome.get();
    }

    @Override
    public void releaseClaim(PendingHandoffClaim claim) {
        if (claim == null) {
            return;
        }
        PendingHandoffKey key = claim.record().key();
        claims.computeIfPresent(key, (ignored, existing) ->
                existing.ownerToken().equals(claim.ownerToken()) ? null : existing
        );
    }

    @Override
    public Optional<PendingHandoffRecord> findByKey(PendingHandoffKey key) {
        StoredEntry entry = records.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.record());
    }

    @Override
    public PendingHandoffReviewPage findReviewPage(String tenantId,
                                                   PendingHandoffStatus status,
                                                   PendingHandoffReviewCursor cursor,
                                                   int limit) {
        requireText(tenantId, "tenantId");
        java.util.Objects.requireNonNull(status, "status cannot be null");
        requireReviewLimit(limit);
        String normalizedTenant = tenantId.trim();

        // 多取一条只用于判断 hasMore，不把额外记录返回给上层。
        List<PendingHandoffRecord> candidates = records.values().stream()
                .map(StoredEntry::record)
                .filter(record -> record.tenantId().equals(normalizedTenant))
                .filter(record -> record.status() == status)
                .filter(record -> isAfterCursor(record, cursor))
                .sorted(REVIEW_ORDER)
                .limit(limit + 1L)
                .toList();
        boolean hasMore = candidates.size() > limit;
        List<PendingHandoffRecord> pageRecords = hasMore
                ? List.copyOf(candidates.subList(0, limit))
                : candidates;
        PendingHandoffReviewCursor nextCursor = hasMore
                ? cursorOf(pageRecords.get(pageRecords.size() - 1))
                : null;
        return new PendingHandoffReviewPage(pageRecords, nextCursor, hasMore);
    }

    @Override
    public Optional<PendingHandoffRecord> findByTenantAndOutboxId(String tenantId, String outboxId) {
        requireText(tenantId, "tenantId");
        requireText(outboxId, "outboxId");
        String normalizedTenant = tenantId.trim();
        String normalizedOutboxId = outboxId.trim();
        return records.values().stream()
                .map(StoredEntry::record)
                .filter(record -> record.tenantId().equals(normalizedTenant))
                .filter(record -> record.outboxId().equals(normalizedOutboxId))
                .findFirst();
    }

    @Override
    public PendingHandoffManualDecisionResult retry(PendingHandoffManualDecisionContext context) {
        return applyManualDecision(context, PendingHandoffManualAction.RETRY, null);
    }

    @Override
    public PendingHandoffManualDecisionResult confirm(PendingHandoffManualDecisionContext context,
                                                      AfterSalesTicketCreateResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        return applyManualDecision(context, PendingHandoffManualAction.CONFIRM, result);
    }

    @Override
    public PendingHandoffManualDecisionResult close(PendingHandoffManualDecisionContext context) {
        return applyManualDecision(context, PendingHandoffManualAction.CLOSE, null);
    }

    @Override
    public PendingHandoffReviewAuditPage findAuditPage(String tenantId,
                                                       String outboxId,
                                                       PendingHandoffReviewAuditCursor cursor,
                                                       int limit) {
        requireText(tenantId, "tenantId");
        requireText(outboxId, "outboxId");
        requireReviewLimit(limit);
        String normalizedTenant = tenantId.trim();
        String normalizedOutboxId = outboxId.trim();

        // 不存在与跨租户都返回同一个空页面，避免管理查询被当成 outboxId 探测器。
        Optional<Map.Entry<PendingHandoffKey, StoredEntry>> optionalEntry = findStoredEntry(
                normalizedTenant,
                normalizedOutboxId
        );
        if (optionalEntry.isEmpty()) {
            return new PendingHandoffReviewAuditPage(List.of(), null, false);
        }

        List<PendingHandoffReviewAuditEvent> candidates = optionalEntry.get().getValue()
                .auditHistory()
                .stream()
                .filter(event -> isAfterAuditCursor(event, cursor))
                .sorted(AUDIT_ORDER)
                .limit(limit + 1L)
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

    public int size() {
        return records.size();
    }

    private PendingHandoffRecord newRecord(PendingHandoffCommand command) {
        long now = System.currentTimeMillis();
        return new PendingHandoffRecord(
                UUID.randomUUID().toString(),
                command.tenantId(),
                command.userId(),
                command.conversationId(),
                command.reason(),
                command.summary(),
                command.request(),
                PendingHandoffStatus.PREPARED,
                "NONE",
                null,
                0,
                safeAdd(now, preparedGraceMs),
                1,
                now,
                now
        );
    }

    private PendingHandoffRecord replace(PendingHandoffKey key,
                                         long expectedVersion,
                                         java.util.function.Function<PendingHandoffRecord, PendingHandoffRecord> updater) {
        StoredEntry existing = requireEntry(key);
        if (existing.record().version() != expectedVersion) {
            throw new PendingHandoffVersionConflictException();
        }
        PendingHandoffRecord updatedRecord = updater.apply(existing.record());
        // Recovery/同步写操作不会产生人工审计，但必须原样携带已有历史，不能在后续状态投影时丢失。
        StoredEntry updated = new StoredEntry(
                existing.fingerprint(),
                updatedRecord,
                existing.auditHistory()
        );
        if (!records.replace(key, existing, updated)) {
            throw new PendingHandoffVersionConflictException();
        }
        return updatedRecord;
    }

    private PendingHandoffRecord copyWithState(PendingHandoffRecord current,
                                               PendingHandoffStatus status,
                                               String failureType,
                                               AfterSalesTicketCreateResult result,
                                               int attemptCount,
                                               long nextAttemptAt) {
        return copyWithStateAt(
                current,
                status,
                failureType,
                result,
                attemptCount,
                nextAttemptAt,
                System.currentTimeMillis()
        );
    }

    private PendingHandoffRecord copyWithStateAt(PendingHandoffRecord current,
                                                 PendingHandoffStatus status,
                                                 String failureType,
                                                 AfterSalesTicketCreateResult result,
                                                 int attemptCount,
                                                 long nextAttemptAt,
                                                 long updatedAt) {
        return new PendingHandoffRecord(
                current.outboxId(),
                current.tenantId(),
                current.userId(),
                current.conversationId(),
                current.reason(),
                current.summary(),
                current.request(),
                status,
                failureType,
                result,
                attemptCount,
                nextAttemptAt,
                current.version() + 1,
                current.createdAt(),
                updatedAt
        );
    }

    private PendingHandoffManualDecisionResult applyManualDecision(
            PendingHandoffManualDecisionContext context,
            PendingHandoffManualAction action,
            AfterSalesTicketCreateResult result) {
        Objects.requireNonNull(context, "context cannot be null");
        Map.Entry<PendingHandoffKey, StoredEntry> located = findStoredEntry(
                context.tenantId(),
                context.outboxId()
        ).orElseThrow(PendingHandoffReviewNotFoundException::new);
        StoredEntry existing = located.getValue();
        PendingHandoffRecord current = existing.record();

        // 先比较调用方看到的版本，再判断状态；这样 stale 页面无法通过异常类型探测最新状态。
        if (current.version() != context.expectedVersion()) {
            throw new PendingHandoffVersionConflictException();
        }
        if (current.status() != PendingHandoffStatus.NEEDS_MANUAL_REVIEW) {
            throw new PendingHandoffStateConflictException(current.status());
        }

        long occurredAt = System.currentTimeMillis();
        PendingHandoffRecord updatedRecord = switch (action) {
            case RETRY -> copyWithStateAt(
                    current,
                    PendingHandoffStatus.RETRY_SCHEDULED,
                    "MANUAL_RETRY_REQUESTED",
                    null,
                    current.attemptCount(),
                    occurredAt,
                    occurredAt
            );
            case CONFIRM -> copyWithStateAt(
                    current,
                    PendingHandoffStatus.CONFIRMED,
                    "NONE",
                    Objects.requireNonNull(result, "result cannot be null"),
                    current.attemptCount(),
                    0,
                    occurredAt
            );
            case CLOSE -> copyWithStateAt(
                    current,
                    PendingHandoffStatus.MANUALLY_CLOSED,
                    "MANUALLY_CLOSED",
                    null,
                    current.attemptCount(),
                    Long.MAX_VALUE,
                    occurredAt
            );
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
                updatedRecord.status(),
                current.version(),
                updatedRecord.version(),
                current.failureType(),
                current.attemptCount(),
                scheduledAt,
                occurredAt
        );

        List<PendingHandoffReviewAuditEvent> updatedHistory = new ArrayList<>(existing.auditHistory());
        updatedHistory.add(auditEvent);
        StoredEntry updated = new StoredEntry(
                existing.fingerprint(),
                updatedRecord,
                List.copyOf(updatedHistory)
        );

        /*
         * record 与 auditHistory 在同一个不可变 StoredEntry 中，且只通过一次 map.replace CAS 发布。
         * 两个管理员同时读取同一版本时，只有一个能替换成功；失败方构造出的事件从未进入 Map，
         * 因而不会出现“业务没有改变但审计声称成功”的伪历史。
         */
        if (!records.replace(located.getKey(), existing, updated)) {
            throw new PendingHandoffVersionConflictException();
        }
        return new PendingHandoffManualDecisionResult(updatedRecord, auditEvent);
    }

    private Optional<Map.Entry<PendingHandoffKey, StoredEntry>> findStoredEntry(
            String tenantId,
            String outboxId) {
        return records.entrySet().stream()
                .filter(entry -> entry.getValue().record().tenantId().equals(tenantId))
                .filter(entry -> entry.getValue().record().outboxId().equals(outboxId))
                .findFirst();
    }

    private boolean isAfterCursor(PendingHandoffRecord record, PendingHandoffReviewCursor cursor) {
        if (cursor == null) {
            return true;
        }
        return record.updatedAt() < cursor.updatedAt()
                || (record.updatedAt() == cursor.updatedAt()
                && record.outboxId().compareTo(cursor.outboxId()) < 0);
    }

    private PendingHandoffReviewCursor cursorOf(PendingHandoffRecord record) {
        return new PendingHandoffReviewCursor(record.updatedAt(), record.outboxId());
    }

    private boolean isAfterAuditCursor(PendingHandoffReviewAuditEvent event,
                                       PendingHandoffReviewAuditCursor cursor) {
        if (cursor == null) {
            return true;
        }
        return event.occurredAt() < cursor.occurredAt()
                || (event.occurredAt() == cursor.occurredAt()
                && event.auditId().compareTo(cursor.auditId()) < 0);
    }

    private PendingHandoffReviewAuditCursor auditCursorOf(PendingHandoffReviewAuditEvent event) {
        return new PendingHandoffReviewAuditCursor(event.occurredAt(), event.auditId());
    }

    private void requireReviewLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private void requireClaimOwner(PendingHandoffClaim claim) {
        if (claim == null) {
            throw new IllegalArgumentException("claim cannot be null");
        }
        PendingHandoffKey key = claim.record().key();
        ClaimEntry existing = claims.get(key);
        if (existing == null
                || !existing.ownerToken().equals(claim.ownerToken())
                || existing.leaseUntil() != claim.leaseUntil()
                || existing.leaseUntil() <= System.currentTimeMillis()) {
            // owner 字符串相同仍不够：旧 Claim 对象的 leaseUntil 也必须匹配且尚未过期。
            // 这样过期 Worker 不能在新 Worker 接管前的短窗口继续安排重试或人工复核。
            throw new PendingHandoffVersionConflictException();
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }

    private void requirePositive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private StoredEntry requireEntry(PendingHandoffKey key) {
        StoredEntry entry = records.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("Pending handoff record does not exist");
        }
        return entry;
    }

    private String safeFailureType(String failureType) {
        if (failureType == null || failureType.isBlank()) {
            return "UNKNOWN";
        }
        String compact = failureType.replaceAll("[^A-Za-z0-9_$.-]", "_");
        return compact.length() <= 128 ? compact : compact.substring(0, 128);
    }

    private record StoredEntry(
            String fingerprint,
            PendingHandoffRecord record,
            List<PendingHandoffReviewAuditEvent> auditHistory) {

        private StoredEntry {
            auditHistory = auditHistory == null ? List.of() : List.copyOf(auditHistory);
        }
    }

    private record ClaimEntry(String ownerToken, long leaseUntil) {
    }
}
