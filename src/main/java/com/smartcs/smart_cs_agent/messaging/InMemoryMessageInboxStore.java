package com.smartcs.smart_cs_agent.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "memory", matchIfMissing = true)
public class InMemoryMessageInboxStore implements MessageInboxStore {
    private static final int CAPACITY = 20_000;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    @Override
    public synchronized boolean claim(String key, String payloadHash, long now) {
        if (entries.containsKey(key)) return false;
        // Receipts are not evicted silently: eviction could execute an old message again.
        if (entries.size() >= CAPACITY) throw new MessageReceiptException("MESSAGE_INBOX_FULL");
        entries.put(key, new Entry(payloadHash, "PROCESSING", null, now));
        return true;
    }

    @Override
    public synchronized Optional<Entry> find(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public synchronized void complete(String key, String responseJson) {
        Entry entry = entries.get(key);
        if (entry == null || !"PROCESSING".equals(entry.status())) {
            throw new IllegalStateException("Message receipt is not owned by this execution");
        }
        entries.put(key, new Entry(entry.payloadHash(), "COMPLETED", responseJson, entry.createdAt()));
    }

    @Override
    public synchronized void markUnknown(String key) {
        Entry entry = entries.get(key);
        if (entry != null && "PROCESSING".equals(entry.status())) {
            entries.put(key, new Entry(entry.payloadHash(), "UNKNOWN", null, entry.createdAt()));
        }
    }
}
