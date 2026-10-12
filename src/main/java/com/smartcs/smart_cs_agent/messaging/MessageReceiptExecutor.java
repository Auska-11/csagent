package com.smartcs.smart_cs_agent.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/** Identity-scoped idempotency for chat and seller events, independent of Agent internals. */
@Service
public class MessageReceiptExecutor {
    private final MessageInboxStore store;
    private final ObjectMapper json;

    public MessageReceiptExecutor(MessageInboxStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    public static String scope(String... coordinates) {
        StringBuilder scope = new StringBuilder();
        for (String coordinate : coordinates) scope.append(coordinate.length()).append(':').append(coordinate);
        return scope.toString();
    }

    public <T> T execute(String scope, String messageId, String payload, Class<T> type, Supplier<T> operation) {
        if (messageId == null || messageId.isBlank()) return operation.get();
        if (messageId.length() > 128) throw new IllegalArgumentException("messageId is too long");
        String key = digest(scope + "\n" + messageId);
        String fingerprint = digest(payload);
        long now = System.currentTimeMillis();
        if (!store.claim(key, fingerprint, now)) {
            MessageInboxStore.Entry existing = store.find(key).orElseThrow();
            if (!existing.payloadHash().equals(fingerprint)) {
                throw new MessageReceiptException("MESSAGE_PAYLOAD_CONFLICT");
            }
            if (!"COMPLETED".equals(existing.status())) {
                String code = "PROCESSING".equals(existing.status()) && now - existing.createdAt() < 60_000
                        ? "MESSAGE_PROCESSING" : "MESSAGE_RESULT_UNKNOWN";
                throw new MessageReceiptException(code);
            }
            try {
                return json.readValue(existing.responseJson(), type);
            } catch (Exception failure) {
                throw new IllegalStateException("Stored message receipt cannot be decoded", failure);
            }
        }
        try {
            T response = operation.get();
            store.complete(key, json.writeValueAsString(response));
            return response;
        } catch (RuntimeException failure) {
            // A business side effect may already have happened. Do not release the claim and retry blindly.
            try { store.markUnknown(key); } catch (RuntimeException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
            throw failure;
        } catch (Exception serializationFailure) {
            store.markUnknown(key);
            throw new IllegalStateException("Message receipt could not be saved", serializationFailure);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
