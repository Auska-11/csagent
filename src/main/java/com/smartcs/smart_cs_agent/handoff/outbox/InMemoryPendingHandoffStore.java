package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
public class InMemoryPendingHandoffStore implements PendingHandoffStore {

    private final Map<String, StoredEntry> records = new ConcurrentHashMap<>();

    @Override
    public PendingHandoffPreparation prepare(PendingHandoffCommand command) {
        String key = command.request().idempotencyKey();
        String fingerprint = PendingHandoffFingerprint.of(command);
        StoredEntry candidate = new StoredEntry(fingerprint, newRecord(command));
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
    public PendingHandoffRecord markResultUnknown(String idempotencyKey,
                                                  String failureType,
                                                  long expectedVersion) {
        StoredEntry existing = requireEntry(idempotencyKey);
        if (existing.record().status() == PendingHandoffStatus.CONFIRMED) {
            // 成功是不可逆终态。迟到的超时线程不能把已确认结果改回未知。
            return existing.record();
        }
        return replace(idempotencyKey, expectedVersion, current -> new PendingHandoffRecord(
                current.outboxId(),
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
    public PendingHandoffRecord confirm(String idempotencyKey,
                                        AfterSalesTicketCreateResult result,
                                        long expectedVersion) {
        if (result == null) {
            throw new IllegalArgumentException("result cannot be null");
        }
        StoredEntry existing = requireEntry(idempotencyKey);
        if (existing.record().status() == PendingHandoffStatus.CONFIRMED) {
            if (!java.util.Objects.equals(existing.record().confirmedResult(), result)) {
                throw new PendingHandoffConflictException();
            }
            return existing.record();
        }
        return replace(idempotencyKey, expectedVersion, current -> new PendingHandoffRecord(
                current.outboxId(),
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
    public Optional<PendingHandoffRecord> findByIdempotencyKey(String idempotencyKey) {
        StoredEntry entry = records.get(idempotencyKey);
        return entry == null ? Optional.empty() : Optional.of(entry.record());
    }

    public int size() {
        return records.size();
    }

    private PendingHandoffRecord newRecord(PendingHandoffCommand command) {
        long now = System.currentTimeMillis();
        return new PendingHandoffRecord(
                UUID.randomUUID().toString(),
                command.userId(),
                command.conversationId(),
                command.reason(),
                command.summary(),
                command.request(),
                PendingHandoffStatus.PREPARED,
                "NONE",
                null,
                0,
                now,
                1,
                now,
                now
        );
    }

    private PendingHandoffRecord replace(String idempotencyKey,
                                         long expectedVersion,
                                         java.util.function.Function<PendingHandoffRecord, PendingHandoffRecord> updater) {
        StoredEntry existing = requireEntry(idempotencyKey);
        if (existing.record().version() != expectedVersion) {
            throw new PendingHandoffVersionConflictException();
        }
        PendingHandoffRecord updatedRecord = updater.apply(existing.record());
        StoredEntry updated = new StoredEntry(existing.fingerprint(), updatedRecord);
        if (!records.replace(idempotencyKey, existing, updated)) {
            throw new PendingHandoffVersionConflictException();
        }
        return updatedRecord;
    }

    private StoredEntry requireEntry(String idempotencyKey) {
        StoredEntry entry = records.get(idempotencyKey);
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

    private record StoredEntry(String fingerprint, PendingHandoffRecord record) {
    }
}
