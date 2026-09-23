package com.smartcs.smart_cs_agent.handoff.sla;

import com.smartcs.smart_cs_agent.persistence.mybatis.mapper.SlaIntegrationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.util.UUID;

import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.association;
import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.payload;
import static com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackFixtures.withContent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 真实 Mapper + H2(MySQL 模式) + Spring 事务下的 SLA 集成持久化：表结构由生产启动路径
 * （构造器 initTables）创建，receive 的“快照 + Inbox + 回填”原子性用注入故障验证。
 */
@SpringBootTest(properties = {
        "smartcs.persistence.mode=jdbc",
        "smartcs.persistence.jdbc.driver-class-name=org.h2.Driver",
        "smartcs.persistence.jdbc.url=jdbc:h2:mem:smartcs_sla_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "smartcs.persistence.jdbc.username=sa",
        "smartcs.persistence.jdbc.password=",
        "smartcs.persistence.jdbc.connect-timeout-ms=1000",
        "smartcs.persistence.jdbc.socket-timeout-ms=2000",
        "smartcs.persistence.jdbc.query-timeout-seconds=2",
        "smartcs.persistence.jdbc.pool-max-size=4"
})
class JdbcSlaIntegrationStoreTest {

    @Autowired
    private JdbcSlaIntegrationStore store;

    @SpyBean
    private SlaIntegrationMapper mapper;

    private String uniqueRequestId() {
        return "wi-v1-" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 27);
    }

    @Test
    void shouldPersistAssociationWithStableMappingAndIdempotentBackfill() {
        String requestId = uniqueRequestId();
        SlaWorkItemAssociation stored = store.recordAssociation(association(requestId));

        assertEquals(requestId, stored.requestId());
        assertTrue(store.findAssociationByRequestId(requestId).isPresent());
        assertNull(store.findAssociationByRequestId(requestId).orElseThrow().workItemId());

        // 恢复执行器重试建单时会再次登记同坐标关联：必须幂等返回而不是报错。
        assertEquals(requestId, store.recordAssociation(association(requestId)).requestId());

        // 同一 requestId 换坐标 = 本地映射损坏，必须暴露给人工检查。
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
        assertThrows(SlaIntegrationConflictException.class,
                () -> store.recordAssociation(moved));

        // workItemId 回填幂等；绑定到不同远端事项必须失败。
        store.attachWorkItemId(requestId, 123L);
        store.attachWorkItemId(requestId, 123L);
        assertEquals(Long.valueOf(123L),
                store.findAssociationByRequestId(requestId).orElseThrow().workItemId());
        assertThrows(SlaIntegrationConflictException.class,
                () -> store.attachWorkItemId(requestId, 456L));
    }

    @Test
    void shouldApplyDuplicateSupersedeAndRejectConflictsThroughTransactions() {
        String requestId = uniqueRequestId();
        store.recordAssociation(association(requestId));

        assertEquals(SlaResultDisposition.APPLIED,
                store.receive(payload(requestId, "event-1", 1, "CONFIRMED")));
        assertEquals(1, store.findCurrentResult(requestId).orElseThrow().version());
        assertEquals(Long.valueOf(123L),
                store.findAssociationByRequestId(requestId).orElseThrow().workItemId());
        assertEquals("event-1", mapper.findInbox("event-1").eventId());

        // 相同事件重试：DUPLICATE，不改快照。
        assertEquals(SlaResultDisposition.DUPLICATE,
                store.receive(payload(requestId, "event-1", 1, "CONFIRMED")));

        // 撤回作为新版本替换快照；随后迟到的旧版本 SUPERSEDED，不回退。
        assertEquals(SlaResultDisposition.APPLIED,
                store.receive(payload(requestId, "event-3", 3, "WITHDRAWN")));
        assertEquals(SlaResultDisposition.SUPERSEDED,
                store.receive(payload(requestId, "event-2", 2, "CONFIRMED")));
        assertEquals(3, store.findCurrentResult(requestId).orElseThrow().version());
        assertEquals("WITHDRAWN", store.findCurrentResult(requestId).orElseThrow().outcome());

        // 同 eventId 异参 / 同版本异内容：协议冲突 409，交给 SLA 侧人工检查。
        SlaResultCallbackRejectedException payloadConflict = assertThrows(
                SlaResultCallbackRejectedException.class,
                () -> store.receive(withContent(
                        payload(requestId, "event-1", 1, "CONFIRMED"), "换了内容"))
        );
        assertEquals("EVENT_PAYLOAD_CONFLICT", payloadConflict.getCode());
        SlaResultCallbackRejectedException versionConflict = assertThrows(
                SlaResultCallbackRejectedException.class,
                () -> store.receive(withContent(
                        payload(requestId, "event-4", 3, "CONFIRMED"), "同版本换内容"))
        );
        assertEquals("VERSION_CONTENT_CONFLICT", versionConflict.getCode());

        // 未知 requestId：404。
        SlaResultCallbackRejectedException notFound = assertThrows(
                SlaResultCallbackRejectedException.class,
                () -> store.receive(payload(uniqueRequestId(), "event-404", 1, "CONFIRMED"))
        );
        assertEquals(404, notFound.getStatus());
    }

    @Test
    void shouldRollbackSnapshotAndBackfillAtomicallyWhenInboxInsertFails() {
        String requestId = uniqueRequestId();
        store.recordAssociation(association(requestId));

        doThrow(new IllegalStateException("injected inbox failure"))
                .when(mapper).insertInbox(any(), any(), any(), any(), anyLong());

        assertThrows(IllegalStateException.class,
                () -> store.receive(payload(requestId, "event-rollback", 1, "CONFIRMED")));

        // Inbox 写失败必须连带回滚快照写入与 workItemId 回填，否则重放会丢失或错配结果。
        assertTrue(store.findCurrentResult(requestId).isEmpty());
        assertNull(store.findAssociationByRequestId(requestId).orElseThrow().workItemId());
        assertNull(mapper.findInbox("event-rollback"));
    }
}
