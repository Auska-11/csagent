package com.smartcs.smart_cs_agent.session.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartcs.smart_cs_agent.agent.context.ConversationStateSnapshot;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** ConversationStateSnapshot 与数据库 JSON 之间的唯一编解码入口。 */
@Component
public class ConversationStateCodec {

    private final ObjectMapper objectMapper;

    public ConversationStateCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    public String encode(ConversationStateSnapshot snapshot) {
        requireSupported(snapshot);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode conversation state", e);
        }
    }

    public ConversationStateSnapshot decode(String json, int storedSchemaVersion) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("conversation state JSON cannot be empty");
        }
        try {
            ConversationStateSnapshot snapshot = objectMapper.readValue(
                    json,
                    ConversationStateSnapshot.class
            );
            if (snapshot.schemaVersion() != storedSchemaVersion) {
                throw new IllegalStateException(
                        "Session schemaVersion column does not match state JSON"
                );
            }
            requireSupported(snapshot);
            return snapshot;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to decode conversation state", e);
        }
    }

    private void requireSupported(ConversationStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");
        if (snapshot.schemaVersion() != ConversationStateSnapshot.CURRENT_SCHEMA_VERSION) {
            throw new UnsupportedSessionSchemaException(
                    snapshot.schemaVersion(),
                    ConversationStateSnapshot.CURRENT_SCHEMA_VERSION
            );
        }
        // Workflow 版本是每个业务流程独立演进的，统一交给 WorkflowStateMigrationRegistry。
        // Codec 只负责 JSON 格式和外层 Conversation schema，避免在这里堆业务迁移分支。
    }
}
