package com.smartcs.smart_cs_agent.handoff.sla;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 本地演示与单元测试使用的 SLA 集成 Store。
 *
 * <p>单 JVM 内保证事务语义（synchronized 全程持锁）；生产 JDBC 模式自动切换到
 * {@link JdbcSlaIntegrationStore}，用数据库事务与行锁实现同样约束。</p>
 */
@Component
@ConditionalOnProperty(
        name = "smartcs.persistence.mode",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemorySlaIntegrationStore implements SlaIntegrationStore {

    private final Map<String, SlaWorkItemAssociation> associationsByRequestId = new HashMap<>();
    private final Map<String, String> requestIdByIdempotencyKey = new HashMap<>();
    private final Map<String, InboxEntry> inboxByEventId = new HashMap<>();
    private final Map<String, SlaResultSnapshot> snapshotsByRequestId = new HashMap<>();

    private record InboxEntry(String requestId, String payloadFingerprint) {
    }

    @Override
    public synchronized SlaWorkItemAssociation recordAssociation(SlaWorkItemAssociation association) {
        Objects.requireNonNull(association, "association cannot be null");
        SlaWorkItemAssociation existing = associationsByRequestId.get(association.requestId());
        if (existing == null) {
            String key = association.tenantId() + "\n" + association.idempotencyKey();
            String existingRequestId = requestIdByIdempotencyKey.get(key);
            if (existingRequestId != null && !existingRequestId.equals(association.requestId())) {
                throw new SlaIntegrationConflictException(
                        "SLA association idempotency key maps to a different requestId"
                );
            }
            associationsByRequestId.put(association.requestId(), association);
            requestIdByIdempotencyKey.put(key, association.requestId());
            return association;
        }
        if (!sameAssociation(existing, association)) {
            throw new SlaIntegrationConflictException(
                    "SLA association coordinates changed for an existing requestId"
            );
        }
        return existing;
    }

    @Override
    public synchronized Optional<SlaWorkItemAssociation> findAssociationByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(associationsByRequestId.get(requestId));
    }

    @Override
    public synchronized void attachWorkItemId(String requestId, long workItemId) {
        SlaWorkItemAssociation existing = associationsByRequestId.get(requestId);
        if (existing == null) {
            throw new SlaIntegrationConflictException("SLA association is missing: " + requestId);
        }
        if (existing.workItemId() == null) {
            associationsByRequestId.put(requestId, existing.withWorkItemId(workItemId));
        } else if (existing.workItemId() != workItemId) {
            throw new SlaIntegrationConflictException(
                    "SLA association is bound to a different work item"
            );
        }
    }

    @Override
    public synchronized SlaResultDisposition receive(SlaResultCallbackPayload payload) {
        Objects.requireNonNull(payload, "payload cannot be null");
        SlaWorkItemAssociation association = associationsByRequestId.get(payload.requestId());
        if (association == null) {
            throw new SlaResultCallbackRejectedException(404, "NOT_FOUND");
        }
        if (!association.matchesCoordinates(
                payload.channel(),
                payload.sellerAccountId(),
                payload.itemId(),
                payload.conversationId(),
                payload.buyerId()
        ) || !association.tenantId().equals(payload.tenantId())) {
            throw new SlaResultCallbackRejectedException(409, "ASSOCIATION_MISMATCH");
        }
        if (association.workItemId() != null
                && association.workItemId() != payload.workItemId().longValue()) {
            throw new SlaResultCallbackRejectedException(409, "ASSOCIATION_MISMATCH");
        }
        InboxEntry inboxEntry = inboxByEventId.get(payload.eventId());
        SlaResultSnapshot current = snapshotsByRequestId.get(payload.requestId());
        SlaResultReceiveDecision decision = SlaResultReceiveDecision.decide(
                payload,
                inboxEntry == null ? null : inboxEntry.payloadFingerprint(),
                current
        );

        // 判定通过后再回填：拒绝的冲突事件不能改变本地关联。
        if (association.workItemId() == null) {
            associationsByRequestId.put(
                    payload.requestId(),
                    association.withWorkItemId(payload.workItemId())
            );
        }

        if (decision.replaceSnapshot()) {
            SlaResultCallbackPayload.Result result = payload.result();
            snapshotsByRequestId.put(payload.requestId(), new SlaResultSnapshot(
                    payload.requestId(),
                    payload.workItemId(),
                    result.version(),
                    result.scope(),
                    result.outcome(),
                    result.content(),
                    result.recordedBy(),
                    result.recordedAt(),
                    System.currentTimeMillis()
            ));
        }
        if (inboxEntry == null) {
            inboxByEventId.put(
                    payload.eventId(),
                    new InboxEntry(payload.requestId(), SlaResultEventFingerprint.of(payload))
            );
        }
        return decision.disposition();
    }

    @Override
    public synchronized Optional<SlaResultSnapshot> findCurrentResult(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshotsByRequestId.get(requestId));
    }

    @Override
    public synchronized Optional<ConversationVerification> findLatestVerification(
            String tenantId, String conversationId) {
        if (tenantId == null || tenantId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        SlaWorkItemAssociation latest = null;
        for (SlaWorkItemAssociation candidate : associationsByRequestId.values()) {
            if (!candidate.tenantId().equals(tenantId)
                    || !candidate.conversationId().equals(conversationId)) {
                continue;
            }
            if (latest == null
                    || candidate.createdAt() > latest.createdAt()
                    || (candidate.createdAt() == latest.createdAt()
                        && candidate.updatedAt() > latest.updatedAt())) {
                latest = candidate;
            }
        }
        if (latest == null) {
            return Optional.empty();
        }
        return Optional.of(new ConversationVerification(
                latest,
                snapshotsByRequestId.get(latest.requestId())
        ));
    }

    private boolean sameAssociation(SlaWorkItemAssociation left, SlaWorkItemAssociation right) {
        return left.tenantId().equals(right.tenantId())
                && left.idempotencyKey().equals(right.idempotencyKey())
                && left.channel().equals(right.channel())
                && left.sellerAccountId().equals(right.sellerAccountId())
                && left.itemId().equals(right.itemId())
                && left.conversationId().equals(right.conversationId())
                && left.buyerId().equals(right.buyerId());
    }
}
