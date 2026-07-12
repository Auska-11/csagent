package com.smartcs.smart_cs_agent.session.api;

import com.smartcs.smart_cs_agent.agent.context.ConversationKey;
import com.smartcs.smart_cs_agent.agent.context.ConversationStateSnapshot;

import java.util.Objects;

/** SessionStore 返回的“状态快照 + 乐观锁版本”。 */
public record SessionRecord(
        ConversationKey key,
        ConversationStateSnapshot snapshot,
        long version,
        long updatedAt
) {

    public SessionRecord {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (version <= 0) {
            throw new IllegalArgumentException("session version must be positive");
        }
        if (!key.userId().equals(snapshot.userId())) {
            throw new IllegalArgumentException("session snapshot owner does not match key");
        }
    }
}
