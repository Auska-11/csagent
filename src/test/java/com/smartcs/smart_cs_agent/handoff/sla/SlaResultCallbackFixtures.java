package com.smartcs.smart_cs_agent.handoff.sla;

/**
 * 回调接收测试共用载荷构造器：字段默认满足契约校验，单个测试按需覆盖个别字段。
 * JDBC 集成测试的 H2 库在 JVM 内跨方法共享，requestId 必须用参数化版本保证唯一。
 */
final class SlaResultCallbackFixtures {

    static final String DEFAULT_REQUEST_ID = "wi-v1-0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef";

    private SlaResultCallbackFixtures() {
    }

    /** 坐标与 {@link #association()} 一致、version 1 的 CONFIRMED 结果。 */
    static SlaResultCallbackPayload payload(String eventId, int version, String outcome) {
        return payload(DEFAULT_REQUEST_ID, eventId, version, outcome);
    }

    static SlaResultCallbackPayload payload(String requestId,
                                            String eventId,
                                            int version,
                                            String outcome) {
        return new SlaResultCallbackPayload(
                1,
                eventId,
                "WORK_ITEM_RESULT_RECORDED",
                "tenant-a",
                123L,
                requestId,
                "XIANYU",
                "seller-1",
                "67890",
                "xianyu_12345_67890",
                "12345",
                new SlaResultCallbackPayload.Result(
                        version,
                        "CONVERSATION",
                        outcome,
                        "卖家已测试，该耳机可与测试手机建立蓝牙连接。",
                        "seller-operator-1",
                        "2026-09-08T02:30:00Z"
                )
        );
    }

    /** 与 {@link #payload(String, int, String)} 同坐标的建单关联（workItemId 未回填）。 */
    static SlaWorkItemAssociation association() {
        return association(DEFAULT_REQUEST_ID);
    }

    static SlaWorkItemAssociation association(String requestId) {
        long now = System.currentTimeMillis();
        return new SlaWorkItemAssociation(
                "tenant-a",
                "agent-handoff-internal-key-" + requestId,
                requestId,
                "XIANYU",
                "seller-1",
                "67890",
                "xianyu_12345_67890",
                "12345",
                null,
                now,
                now
        );
    }

    static SlaResultCallbackPayload withEventId(SlaResultCallbackPayload payload, String eventId) {
        return new SlaResultCallbackPayload(
                payload.schemaVersion(),
                eventId,
                payload.eventType(),
                payload.tenantId(),
                payload.workItemId(),
                payload.requestId(),
                payload.channel(),
                payload.sellerAccountId(),
                payload.itemId(),
                payload.conversationId(),
                payload.buyerId(),
                payload.result()
        );
    }

    static SlaResultCallbackPayload withContent(SlaResultCallbackPayload payload, String content) {
        SlaResultCallbackPayload.Result result = payload.result();
        return new SlaResultCallbackPayload(
                payload.schemaVersion(),
                payload.eventId(),
                payload.eventType(),
                payload.tenantId(),
                payload.workItemId(),
                payload.requestId(),
                payload.channel(),
                payload.sellerAccountId(),
                payload.itemId(),
                payload.conversationId(),
                payload.buyerId(),
                new SlaResultCallbackPayload.Result(
                        result.version(),
                        result.scope(),
                        result.outcome(),
                        content,
                        result.recordedBy(),
                        result.recordedAt()
                )
        );
    }

    static SlaResultCallbackPayload withConversation(SlaResultCallbackPayload payload,
                                                     String conversationId,
                                                     String buyerId,
                                                     String itemId) {
        return new SlaResultCallbackPayload(
                payload.schemaVersion(),
                payload.eventId(),
                payload.eventType(),
                payload.tenantId(),
                payload.workItemId(),
                payload.requestId(),
                payload.channel(),
                payload.sellerAccountId(),
                itemId,
                conversationId,
                buyerId,
                payload.result()
        );
    }
}
