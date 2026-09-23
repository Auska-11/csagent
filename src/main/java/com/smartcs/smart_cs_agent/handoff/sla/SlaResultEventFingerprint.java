package com.smartcs.smart_cs_agent.handoff.sla;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 回调事件载荷指纹：同一 eventId 下判定“相同载荷”还是“不同载荷”。
 *
 * <p>覆盖事件的全部实质字段（含 result 全字段）。相同 eventId、不同指纹即契约中的
 * EVENT_PAYLOAD_CONFLICT，必须 409 报警而不是静默接受。</p>
 */
final class SlaResultEventFingerprint {

    private SlaResultEventFingerprint() {
    }

    static String of(SlaResultCallbackPayload payload) {
        SlaResultCallbackPayload.Result result = payload.result();
        String canonical = segment(String.valueOf(payload.schemaVersion()))
                + segment(payload.eventId())
                + segment(payload.eventType())
                + segment(payload.tenantId())
                + segment(String.valueOf(payload.workItemId()))
                + segment(payload.requestId())
                + segment(payload.channel())
                + segment(payload.sellerAccountId())
                + segment(payload.itemId())
                + segment(payload.conversationId())
                + segment(payload.buyerId())
                + segment(String.valueOf(result.version()))
                + segment(result.scope())
                + segment(result.outcome())
                + segment(result.content())
                + segment(result.recordedBy())
                + segment(result.recordedAt());
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
