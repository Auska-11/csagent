package com.smartcs.smart_cs_agent.handoff.sla;

import com.smartcs.smart_cs_agent.persistence.JdbcDuplicateKeyException;
import com.smartcs.smart_cs_agent.persistence.MyBatisMapperExecutor;
import com.smartcs.smart_cs_agent.persistence.PersistenceTransactionExecutor;
import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.SlaIntegrationMapper;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.SlaAssociationRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.SlaResultRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 基于 MyBatis 的 SLA v1 集成 Store。
 *
 * <p>receive() 在单个 Spring 事务中完成：按 requestId 锁定关联行（串行化同一事项的并发回调）→
 * 校验坐标 → Inbox 去重 → 版本比较 → 快照替换 → Inbox 落库。任何一步抛出协议拒绝异常都整体
 * 回滚，绝不会出现“记了 Inbox 却没更新快照”或反之的半提交状态。</p>
 */
@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
public class JdbcSlaIntegrationStore implements SlaIntegrationStore {

    private final SlaIntegrationMapper mapper;
    private final MyBatisMapperExecutor mapperExecutor;
    private final PersistenceTransactionExecutor transactionExecutor;

    public JdbcSlaIntegrationStore(SlaIntegrationMapper mapper,
                                   MyBatisMapperExecutor mapperExecutor,
                                   PersistenceTransactionExecutor transactionExecutor) {
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
        this.mapperExecutor = Objects.requireNonNull(
                mapperExecutor,
                "mapperExecutor cannot be null"
        );
        this.transactionExecutor = Objects.requireNonNull(
                transactionExecutor,
                "transactionExecutor cannot be null"
        );
        initTables();
    }

    private void initTables() {
        dbRun(mapper::createAssociationTable);
        dbRun(mapper::createInboxTable);
        dbRun(mapper::createResultTable);
    }

    @Override
    public SlaWorkItemAssociation recordAssociation(SlaWorkItemAssociation association) {
        Objects.requireNonNull(association, "association cannot be null");
        try {
            int changed = db(() -> mapper.insertAssociation(
                    association.requestId(),
                    association.tenantId(),
                    association.idempotencyKey(),
                    association.channel(),
                    association.sellerAccountId(),
                    association.itemId(),
                    association.conversationId(),
                    association.buyerId(),
                    association.createdAt()
            ));
            if (changed != 1) {
                throw new IllegalStateException(
                        "SLA association insert did not change exactly one row"
                );
            }
            return association;
        } catch (JdbcDuplicateKeyException duplicateKey) {
            SlaAssociationRow existing = db(() -> mapper.findAssociationByRequestId(
                    association.requestId()
            ));
            if (existing == null) {
                // request_id 唯一键未命中说明冲突发生在 (tenant_id, idempotency_key) 主键：
                // 同一本地操作被映射到了不同 requestId，属于必须人工介入的映射损坏。
                throw new SlaIntegrationConflictException(
                        "SLA association idempotency key maps to a different requestId"
                );
            }
            SlaWorkItemAssociation stored = toAssociation(existing);
            if (!stored.matchesCoordinates(
                    association.channel(),
                    association.sellerAccountId(),
                    association.itemId(),
                    association.conversationId(),
                    association.buyerId()
            ) || !stored.tenantId().equals(association.tenantId())
                    || !stored.idempotencyKey().equals(association.idempotencyKey())) {
                throw new SlaIntegrationConflictException(
                        "SLA association coordinates changed for an existing requestId"
                );
            }
            return stored;
        }
    }

    @Override
    public Optional<SlaWorkItemAssociation> findAssociationByRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return db(() -> Optional.ofNullable(
                mapper.findAssociationByRequestId(requestId)
        ).map(this::toAssociation));
    }

    @Override
    public void attachWorkItemId(String requestId, long workItemId) {
        long now = System.currentTimeMillis();
        int changed = db(() -> mapper.updateAssociationWorkItemId(requestId, workItemId, now));
        if (changed == 1) {
            return;
        }
        SlaAssociationRow existing = db(() -> mapper.findAssociationByRequestId(requestId));
        if (existing == null) {
            throw new SlaIntegrationConflictException("SLA association is missing: " + requestId);
        }
        if (existing.workItemId() != null && existing.workItemId() != workItemId) {
            throw new SlaIntegrationConflictException(
                    "SLA association is bound to a different work item"
            );
        }
        // workItemId 已是同一值：幂等重放，无需更新。
    }

    @Override
    public SlaResultDisposition receive(SlaResultCallbackPayload payload) {
        Objects.requireNonNull(payload, "payload cannot be null");
        long now = System.currentTimeMillis();
        return transactionExecutor.required(() -> {
            // 关联行锁是同一事项并发回调的串行化点；随后的 Inbox/快照读写都在该锁保护内。
            SlaAssociationRow row = db(() -> mapper.findAssociationByRequestIdForUpdate(
                    payload.requestId()
            ));
            if (row == null) {
                throw new SlaResultCallbackRejectedException(404, "NOT_FOUND");
            }
            if (!toAssociation(row).matchesCoordinates(
                    payload.channel(),
                    payload.sellerAccountId(),
                    payload.itemId(),
                    payload.conversationId(),
                    payload.buyerId()
            ) || !row.tenantId().equals(payload.tenantId())) {
                throw new SlaResultCallbackRejectedException(409, "ASSOCIATION_MISMATCH");
            }
            if (row.workItemId() != null
                    && row.workItemId().longValue() != payload.workItemId().longValue()) {
                throw new SlaResultCallbackRejectedException(409, "ASSOCIATION_MISMATCH");
            }
            if (row.workItemId() == null) {
                int changed = db(() -> mapper.updateAssociationWorkItemId(
                        payload.requestId(),
                        payload.workItemId(),
                        now
                ));
                if (changed != 1) {
                    throw new IllegalStateException(
                            "SLA association work item backfill did not change exactly one row"
                    );
                }
            }

            String inboxFingerprint = db(() -> Optional.ofNullable(mapper.findInbox(payload.eventId())))
                    .map(inboxRow -> {
                        if (!inboxRow.requestId().equals(payload.requestId())) {
                            throw new SlaResultCallbackRejectedException(
                                    409,
                                    "EVENT_PAYLOAD_CONFLICT"
                            );
                        }
                        return inboxRow.payloadHash();
                    })
                    .orElse(null);

            SlaResultSnapshot current = db(() -> Optional.ofNullable(mapper.findResult(payload.requestId())))
                    .map(this::toSnapshot)
                    .orElse(null);

            SlaResultReceiveDecision decision = SlaResultReceiveDecision.decide(
                    payload,
                    inboxFingerprint,
                    current
            );

            if (decision.replaceSnapshot()) {
                int changed;
                if (current == null) {
                    changed = db(() -> mapper.insertResult(payload, now));
                } else {
                    changed = db(() -> mapper.updateResult(payload, now));
                }
                if (changed != 1) {
                    throw new IllegalStateException(
                            "SLA result snapshot write did not change exactly one row"
                    );
                }
            }
            if (inboxFingerprint == null) {
                try {
                    int changed = db(() -> mapper.insertInbox(
                            payload.eventId(),
                            payload.requestId(),
                            SlaResultEventFingerprint.of(payload),
                            decision.disposition().name(),
                            now
                    ));
                    if (changed != 1) {
                        throw new IllegalStateException(
                                "SLA result inbox insert did not change exactly one row"
                        );
                    }
                } catch (JdbcDuplicateKeyException duplicateKey) {
                    // 关联行锁已排除同 requestId 的并发插入；仍冲突说明同一 eventId 被
                    // 投给了不同事项或不同载荷，必须按协议冲突暴露而不是静默接受。
                    throw new SlaResultCallbackRejectedException(409, "EVENT_PAYLOAD_CONFLICT");
                }
            }
            return decision.disposition();
        });
    }

    @Override
    public Optional<SlaResultSnapshot> findCurrentResult(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return db(() -> Optional.ofNullable(mapper.findResult(requestId))
                .map(this::toSnapshot));
    }

    @Override
    public Optional<ConversationVerification> findLatestVerification(
            String tenantId, String conversationId) {
        if (tenantId == null || tenantId.isBlank() || conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return db(() -> Optional.ofNullable(
                mapper.findLatestAssociationByConversation(tenantId, conversationId)
        ).map(row -> new ConversationVerification(
                toAssociation(row),
                Optional.ofNullable(mapper.findResult(row.requestId())).map(this::toSnapshot).orElse(null)
        )));
    }

    private SlaWorkItemAssociation toAssociation(SlaAssociationRow row) {
        return new SlaWorkItemAssociation(
                row.tenantId(),
                row.idempotencyKey(),
                row.requestId(),
                row.channel(),
                row.sellerAccountId(),
                row.itemId(),
                row.conversationId(),
                row.buyerId(),
                row.workItemId(),
                row.createdAt(),
                row.updatedAt()
        );
    }

    private SlaResultSnapshot toSnapshot(SlaResultRow row) {
        return new SlaResultSnapshot(
                row.requestId(),
                row.workItemId(),
                row.version(),
                row.scope(),
                row.outcome(),
                row.content(),
                row.recordedBy(),
                row.recordedAt(),
                row.updatedAt()
        );
    }

    private <T> T db(Supplier<T> operation) {
        return mapperExecutor.execute(operation);
    }

    private void dbRun(Runnable operation) {
        mapperExecutor.run(operation);
    }
}
