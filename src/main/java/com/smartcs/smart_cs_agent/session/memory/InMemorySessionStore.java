package com.smartcs.smart_cs_agent.session.memory;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStateSnapshot;
import com.smartcs.smart_cs_agent.session.api.SessionRecord;
import com.smartcs.smart_cs_agent.session.api.SessionStore;
import com.smartcs.smart_cs_agent.session.api.SessionVersionConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** 默认的单进程 SessionStore，适合单元测试、本地学习和 Demo。 */
@Component
@ConditionalOnProperty(
        name = "smartcs.persistence.mode",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemorySessionStore implements SessionStore {

    private final Map<ConversationKey, SessionRecord> sessions = new ConcurrentHashMap<>();

    @Override
    public Optional<SessionRecord> load(ConversationKey key) {
        return Optional.ofNullable(sessions.get(key));
    }

    @Override
    public SessionRecord save(
            ConversationKey key,
            long expectedVersion,
            ConversationStateSnapshot snapshot) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion cannot be negative");
        }

        AtomicReference<SessionRecord> saved = new AtomicReference<>();
        sessions.compute(key, (ignored, existing) -> {
            long actualVersion = existing == null ? 0 : existing.version();
            if (actualVersion != expectedVersion) {
                throw new SessionVersionConflictException(key, expectedVersion, actualVersion);
            }

            SessionRecord next = new SessionRecord(
                    key,
                    snapshot,
                    expectedVersion + 1,
                    System.currentTimeMillis()
            );
            saved.set(next);
            return next;
        });
        return saved.get();
    }

    @Override
    public boolean delete(ConversationKey key, long expectedVersion) {
        if (expectedVersion <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        AtomicReference<Boolean> deleted = new AtomicReference<>(false);
        sessions.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return null;
            }
            if (existing.version() != expectedVersion) {
                throw new SessionVersionConflictException(
                        key,
                        expectedVersion,
                        existing.version()
                );
            }
            deleted.set(true);
            return null;
        });
        return deleted.get();
    }
}
