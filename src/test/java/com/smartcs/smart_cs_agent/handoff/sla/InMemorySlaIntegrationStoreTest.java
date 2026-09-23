package com.smartcs.smart_cs_agent.handoff.sla;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.association;
import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.payload;
import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.withContent;
import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.withConversation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回调接收的契约判定：去重、版本、撤回与坐标校验。JDBC Store 复用同一套判定逻辑
 * （SlaResultReceiveDecision），这里覆盖的是全部判定分支。
 */
class InMemorySlaIntegrationStoreTest {

    @Test
    void shouldApplyFirstResultAndBackfillWorkItemId() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();
        store.recordAssociation(association());

        SlaResultDisposition disposition = store.receive(payload("sla-event-1", 1, "CONFIRMED"));

        assertEquals(SlaResultDisposition.APPLIED, disposition);
        SlaResultSnapshot snapshot = store.findCurrentResult(payload("x", 1, "CONFIRMED")
                .requestId()).orElseThrow();
        assertEquals(1, snapshot.version());
        assertEquals("CONFIRMED", snapshot.outcome());
        assertEquals(123L, snapshot.workItemId());
        // 回调先于建单响应到达：workItemId 在接收时回填到关联。
        assertEquals(123L, store.findAssociationByRequestId(snapshot.requestId())
                .orElseThrow()
                .workItemId());
    }

    @Test
    void shouldReturnDuplicateForSameEventReplay() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();
        store.recordAssociation(association());
        store.receive(payload("sla-event-1", 1, "CONFIRMED"));

        assertEquals(
                SlaResultDisposition.DUPLICATE,
                store.receive(payload("sla-event-1", 1, "CONFIRMED"))
        );
    }

    @Test
    void shouldRejectSameEventIdWithDifferentPayload() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();
        store.recordAssociation(association());
        store.receive(payload("sla-event-1", 1, "CONFIRMED"));

        SlaResultCallbackRejectedException rejected = assertThrows(
                SlaResultCallbackRejectedException.class,
                () -> store.receive(withContent(payload("sla-event-1", 1, "CONFIRMED"), "不同内容"))
        );

        assertEquals(409, rejected.getStatus());
        assertEquals("EVENT_PAYLOAD_CONFLICT", rejected.getCode());
    }

    @Test
    void shouldApplyNewerVersionAndReplaceWithdrawnSnapshots() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();
        store.recordAssociation(association());
        store.receive(payload("sla-event-1", 1, "CONFIRMED"));

        // 版本可以跳跃：直接收到 v3 撤回，按完整新快照替换。
        assertEquals(
                SlaResultDisposition.APPLIED,
                store.receive(payload("sla-event-3", 3, "WITHDRAWN"))
        );
        assertEquals(3, store.findCurrentResult(association().requestId())
                .orElseThrow()
                .version());
        assertEquals("WITHDRAWN", store.findCurrentResult(association().requestId())
                .orElseThrow()
                .outcome());

        // 撤回后迟到的旧版本不得回退状态。
        assertEquals(
                SlaResultDisposition.SUPERSEDED,
                store.receive(payload("sla-event-2", 2, "CONFIRMED"))
        );
        assertEquals(3, store.findCurrentResult(association().requestId())
                .orElseThrow()
                .version());
    }

    @Test
    void shouldTreatSameVersionSameContentDifferentEventAsDuplicate() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();
        store.recordAssociation(association());
        store.receive(payload("sla-event-1", 1, "CONFIRMED"));

        assertEquals(
                SlaResultDisposition.DUPLICATE,
                store.receive(payload("sla-event-1-replayed-by-sla", 1, "CONFIRMED"))
        );
    }

    @Test
    void shouldRejectSameVersionDifferentContent() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();
        store.recordAssociation(association());
        store.receive(payload("sla-event-1", 1, "CONFIRMED"));

        SlaResultCallbackRejectedException rejected = assertThrows(
                SlaResultCallbackRejectedException.class,
                () -> store.receive(withContent(
                        payload("sla-event-2", 1, "CONFIRMED"),
                        "同一版本却换了内容"
                ))
        );

        assertEquals(409, rejected.getStatus());
        assertEquals("VERSION_CONTENT_CONFLICT", rejected.getCode());
    }

    @Test
    void rejectedResultMustNotBackfillWorkItemId() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();
        String requestId = "wi-v1-second-request";
        store.recordAssociation(association(requestId));
        store.receive(payload(requestId, "sla-event-second-1", 1, "CONFIRMED"));

        // 同 eventId 异请求必须拒绝，且不能提前回填新关联的工单 ID。
        String thirdRequestId = "wi-v1-third-request";
        store.recordAssociation(association(thirdRequestId));
        assertThrows(SlaResultCallbackRejectedException.class,
                () -> store.receive(payload(thirdRequestId, "sla-event-second-1", 1, "CONFIRMED")));
        assertNull(store.findAssociationByRequestId(thirdRequestId).orElseThrow().workItemId());
    }

    @Test
    void shouldRejectUnknownRequestOrMismatchedCoordinates() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();

        // 未登记过的 requestId：404，让 SLA 侧重试而不是误认为成功。
        SlaResultCallbackRejectedException notFound = assertThrows(
                SlaResultCallbackRejectedException.class,
                () -> store.receive(payload("sla-event-1", 1, "CONFIRMED"))
        );
        assertEquals(404, notFound.getStatus());

        store.recordAssociation(association());

        // 其他买家、其他商品、其他会话不能获得该结果。
        SlaResultCallbackRejectedException mismatch = assertThrows(
                SlaResultCallbackRejectedException.class,
                () -> store.receive(withConversation(
                        payload("sla-event-1", 1, "CONFIRMED"),
                        "xianyu_99999_67890",
                        "99999",
                        "67890"
                ))
        );
        assertEquals(409, mismatch.getStatus());
        assertEquals("ASSOCIATION_MISMATCH", mismatch.getCode());

        // 绑定了别的 workItemId 的回调同样拒绝。
        SlaResultCallbackPayload otherWorkItem = new SlaResultCallbackPayload(
                1,
                "sla-event-9",
                "WORK_ITEM_RESULT_RECORDED",
                "tenant-a",
                456L,
                association().requestId(),
                "XIANYU",
                "seller-1",
                "67890",
                "xianyu_12345_67890",
                "12345",
                payload("x", 1, "CONFIRMED").result()
        );
        store.attachWorkItemId(association().requestId(), 123L);
        SlaResultCallbackRejectedException wrongWorkItem = assertThrows(
                SlaResultCallbackRejectedException.class,
                () -> store.receive(otherWorkItem)
        );
        assertEquals("ASSOCIATION_MISMATCH", wrongWorkItem.getCode());
    }

    @Test
    void shouldKeepAssociationMappingStable() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();
        SlaWorkItemAssociation stored = store.recordAssociation(association());

        // 重复登记同坐标关联是幂等重放（恢复执行器重试建单时会再登记一次）。
        assertEquals(stored.requestId(), store.recordAssociation(association()).requestId());

        // 同一 requestId 换坐标 = 本地映射损坏，必须暴露。
        SlaWorkItemAssociation moved = new SlaWorkItemAssociation(
                stored.tenantId(),
                stored.idempotencyKey(),
                stored.requestId(),
                "XIANYU",
                "seller-1",
                "11111",
                "xianyu_12345_11111",
                "12345",
                null,
                stored.createdAt(),
                stored.updatedAt()
        );
        assertThrows(SlaIntegrationConflictException.class, () -> store.recordAssociation(moved));

        // workItemId 只回填一次，不覆盖。
        store.attachWorkItemId(stored.requestId(), 123L);
        store.attachWorkItemId(stored.requestId(), 123L);
        assertThrows(SlaIntegrationConflictException.class,
                () -> store.attachWorkItemId(stored.requestId(), 456L));
        assertEquals(123L, store.findAssociationByRequestId(stored.requestId())
                .orElseThrow()
                .workItemId());
        assertThrows(SlaIntegrationConflictException.class,
                () -> store.attachWorkItemId("wi-v1-missing", 1L));
    }

    @Test
    void shouldFindNothingForUnknownRequest() {
        InMemorySlaIntegrationStore store = new InMemorySlaIntegrationStore();

        assertTrue(store.findAssociationByRequestId("wi-v1-unknown").isEmpty());
        assertTrue(store.findCurrentResult("wi-v1-unknown").isEmpty());
        assertTrue(store.findAssociationByRequestId(null).isEmpty());
        assertTrue(store.findCurrentResult("  ").isEmpty());
    }
}
