package com.smartcs.smart_cs_agent.handoff.outbox;

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
    public PendingHandoffRecord recordUnknown(PendingHandoffCommand command) {
        String key = command.request().idempotencyKey();
        String fingerprint = PendingHandoffFingerprint.of(command);
        StoredEntry candidate = new StoredEntry(fingerprint, newRecord(command));
        StoredEntry existing = records.putIfAbsent(key, candidate);
        if (existing == null) {
            return candidate.record();
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new PendingHandoffConflictException();
        }
        return existing.record();
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
                PendingHandoffStatus.RESULT_UNKNOWN,
                command.failureType(),
                0,
                now,
                1,
                now,
                now
        );
    }

    private record StoredEntry(String fingerprint, PendingHandoffRecord record) {
    }
}
