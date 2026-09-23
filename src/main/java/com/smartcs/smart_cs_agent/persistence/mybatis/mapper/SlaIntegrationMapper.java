package com.smartcs.smart_cs_agent.persistence.mybatis.mapper;

import com.smartcs.smart_cs_agent.handoff.sla.SlaResultCallbackPayload;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.SlaAssociationRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.SlaInboxRow;
import com.smartcs.smart_cs_agent.persistence.mybatis.row.SlaResultRow;
import org.apache.ibatis.annotations.Param;

/** SLA v1 集成（建单关联/回调 Inbox/结果快照）的 MyBatis 映射。 */
public interface SlaIntegrationMapper {

    void createAssociationTable();

    void createInboxTable();

    void createResultTable();

    int insertAssociation(
            @Param("requestId") String requestId,
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("channel") String channel,
            @Param("sellerAccountId") String sellerAccountId,
            @Param("itemId") String itemId,
            @Param("conversationId") String conversationId,
            @Param("buyerId") String buyerId,
            @Param("now") long now);

    SlaAssociationRow findAssociationByRequestId(@Param("requestId") String requestId);

    SlaAssociationRow findAssociationByRequestIdForUpdate(@Param("requestId") String requestId);

    SlaAssociationRow findAssociationByIdempotencyKey(
            @Param("tenantId") String tenantId,
            @Param("idempotencyKey") String idempotencyKey);

    /** 会话最近一次核实关联；createdAt 优先，其次 updatedAt、requestId 保证稳定排序。 */
    SlaAssociationRow findLatestAssociationByConversation(
            @Param("tenantId") String tenantId,
            @Param("conversationId") String conversationId);

    int updateAssociationWorkItemId(
            @Param("requestId") String requestId,
            @Param("workItemId") long workItemId,
            @Param("now") long now);

    int insertInbox(
            @Param("eventId") String eventId,
            @Param("requestId") String requestId,
            @Param("payloadHash") String payloadHash,
            @Param("disposition") String disposition,
            @Param("receivedAt") long receivedAt);

    SlaInboxRow findInbox(@Param("eventId") String eventId);

    int insertResult(
            @Param("payload") SlaResultCallbackPayload payload,
            @Param("updatedAt") long updatedAt);

    int updateResult(
            @Param("payload") SlaResultCallbackPayload payload,
            @Param("updatedAt") long updatedAt);

    SlaResultRow findResult(@Param("requestId") String requestId);

    SlaResultRow findResultForUpdate(@Param("requestId") String requestId);
}
