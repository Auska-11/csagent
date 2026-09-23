package com.smartcs.smart_cs_agent.handoff.sla;

import java.util.Objects;

/**
 * 回调接收的纯判定逻辑，供 memory/JDBC 两种 Store 共用，保证语义不漂移。
 *
 * <p>输入：新载荷、该 eventId 的既有指纹（无则 null）、该 requestId 当前快照（无则 null）。
 * 输出：处置结果与是否更新快照；协议冲突抛 {@link SlaResultCallbackRejectedException}。</p>
 */
record SlaResultReceiveDecision(
        SlaResultDisposition disposition,
        boolean replaceSnapshot
) {

    static SlaResultReceiveDecision decide(SlaResultCallbackPayload payload,
                                           String existingEventFingerprint,
                                           SlaResultSnapshot currentSnapshot) {
        String fingerprint = SlaResultEventFingerprint.of(payload);

        if (existingEventFingerprint != null) {
            if (!existingEventFingerprint.equals(fingerprint)) {
                throw new SlaResultCallbackRejectedException(409, "EVENT_PAYLOAD_CONFLICT");
            }
            // 相同事件重试：已处理过，直接确认，不改变上下文。
            return new SlaResultReceiveDecision(SlaResultDisposition.DUPLICATE, false);
        }

        if (currentSnapshot == null || currentSnapshot.version() < payload.result().version()) {
            return new SlaResultReceiveDecision(SlaResultDisposition.APPLIED, true);
        }
        if (currentSnapshot.version() > payload.result().version()) {
            return new SlaResultReceiveDecision(SlaResultDisposition.SUPERSEDED, false);
        }

        if (sameResultContent(payload, currentSnapshot)) {
            // 不同 eventId、同一版本同一内容：按 DUPLICATE 确认，不重复更新。
            return new SlaResultReceiveDecision(SlaResultDisposition.DUPLICATE, false);
        }
        throw new SlaResultCallbackRejectedException(409, "VERSION_CONTENT_CONFLICT");
    }

    private static boolean sameResultContent(SlaResultCallbackPayload payload,
                                             SlaResultSnapshot snapshot) {
        SlaResultCallbackPayload.Result result = payload.result();
        return Objects.equals(result.outcome(), snapshot.outcome())
                && Objects.equals(result.scope(), snapshot.scope())
                && Objects.equals(result.content(), snapshot.content())
                && Objects.equals(result.recordedBy(), snapshot.recordedBy());
    }
}
