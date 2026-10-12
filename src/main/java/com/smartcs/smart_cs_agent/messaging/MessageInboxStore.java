package com.smartcs.smart_cs_agent.messaging;

import java.util.Optional;

/** Durable execution receipts. A started turn is never taken over after a crash. */
public interface MessageInboxStore {
    record Entry(String payloadHash, String status, String responseJson, long createdAt) { }

    boolean claim(String key, String payloadHash, long now);
    Optional<Entry> find(String key);
    void complete(String key, String responseJson);
    void markUnknown(String key);
}
