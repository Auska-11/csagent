package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 生成稳定请求指纹，供 memory/JDBC 两种 Store 共用。 */
final class PendingHandoffFingerprint {

    private PendingHandoffFingerprint() {
    }

    static String of(PendingHandoffCommand command) {
        AfterSalesTicketCreateRequest request = command.request();
        String canonical = segment(command.userId())
                + segment(command.conversationId())
                + segment(command.reason())
                + segment(command.summary())
                + segment(request.title())
                + segment(request.description())
                + segment(request.customerId())
                + segment(request.productLine())
                + segment(request.issueType())
                + segment(request.priority())
                + segment(request.idempotencyKey());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String segment(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }
}
