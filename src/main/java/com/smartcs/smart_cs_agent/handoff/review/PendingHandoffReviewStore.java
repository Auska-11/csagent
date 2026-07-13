package com.smartcs.smart_cs_agent.handoff.review;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;

import java.util.Optional;

/**
 * 人工复核专用持久化端口。
 *
 * <p>它与 Recovery Worker 使用的 PendingHandoffStore 分开，避免后台机器租约和管理端查询
 * 挤进同一个大接口。这个端口从 20.3 起严格只读；所有人工状态变更必须经过
 * {@link PendingHandoffManualDecisionStore}，否则就可能改变业务状态却漏记审计。</p>
 */
public interface PendingHandoffReviewStore {

    /**
     * 按 updatedAt DESC、outboxId DESC 做稳定 keyset 分页。
     * limit 只允许 1..100，防止管理接口一次读取无界敏感数据。
     */
    PendingHandoffReviewPage findReviewPage(String tenantId,
                                            PendingHandoffStatus status,
                                            PendingHandoffReviewCursor cursor,
                                            int limit);

    /** 只在指定租户内按真正的 Outbox 主键查找；跨租户与不存在都返回 empty。 */
    Optional<PendingHandoffRecord> findByTenantAndOutboxId(String tenantId, String outboxId);
}
