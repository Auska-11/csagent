package com.smartcs.smart_cs_agent.handoff.sla;

import java.util.Optional;

/**
 * SLA v1 集成的本地持久化端口：建单关联 + 回调 Inbox + 当前结果快照。
 *
 * <p>receive 必须把 Inbox 记录与快照更新放在同一持久化事务中——仅写内存或打日志不能
 * 作为回调成功确认（契约“结果回传接口”一节）。</p>
 */
public interface SlaIntegrationStore {

    /**
     * 建单调用前登记 requestId 关联；同键重复登记返回已存记录。
     *
     * @throws SlaIntegrationConflictException 同一 requestId/幂等键对应了不同坐标（本地映射损坏）
     */
    SlaWorkItemAssociation recordAssociation(SlaWorkItemAssociation association);

    Optional<SlaWorkItemAssociation> findAssociationByRequestId(String requestId);

    /** 建单成功后回填远端 workItemId；仅在为空时写入，不覆盖回调已回填的值。 */
    void attachWorkItemId(String requestId, long workItemId);

    /**
     * 接收一次结果回调：查关联、校验坐标、Inbox 去重、版本比较、必要时替换快照，
     * 全部在同一事务中完成。
     *
     * @throws SlaResultCallbackRejectedException 404（无关联）或 409（坐标/事件/版本冲突）
     */
    SlaResultDisposition receive(SlaResultCallbackPayload payload);

    Optional<SlaResultSnapshot> findCurrentResult(String requestId);

    /**
     * 查询某会话最近一次商品核实关联及其当前生效结果。
     *
     * <p>同一会话可能因幂等键包含 operation 而产生多个 requestId，消费方只关心最新一次
     * （createdAt 优先，其次 updatedAt）。currentResult 为 null 表示核实仍在进行、
     * 结果尚未回调。</p>
     */
    Optional<ConversationVerification> findLatestVerification(String tenantId, String conversationId);

    /**
     * 会话维度的一次商品核实。association 永远存在；currentResult 可能为 null
     * （建单成功但卖家尚未提交结果）。
     */
    record ConversationVerification(SlaWorkItemAssociation association,
                                    SlaResultSnapshot currentResult) {
    }
}
