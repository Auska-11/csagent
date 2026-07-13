package com.smartcs.smart_cs_agent.handoff.review;

import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffRecord;
import com.smartcs.smart_cs_agent.handoff.outbox.PendingHandoffStatus;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;

import java.util.Optional;

/**
 * 人工复核专用持久化端口。
 *
 * <p>它与 Recovery Worker 使用的 PendingHandoffStore 分开，避免后台机器租约、HTTP 分页和人工状态机
 * 挤进同一个大接口。两个端口暂时由同一个 memory/JDBC Adapter 实现，但上层职责已经解耦。</p>
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

    /** 人工决定再次交给 Worker；这里只排队，不在管理 HTTP 线程同步调用远端。 */
    PendingHandoffRecord retryFromManualReview(String tenantId,
                                               String outboxId,
                                               long expectedVersion,
                                               long scheduledAt);

    /** 人工根据外部证据确认真实工单已经存在。 */
    PendingHandoffRecord confirmFromManualReview(String tenantId,
                                                 String outboxId,
                                                 AfterSalesTicketCreateResult result,
                                                 long expectedVersion);

    /** 人工确认无需再创建工单，进入不可自动恢复的关闭终态。 */
    PendingHandoffRecord closeFromManualReview(String tenantId,
                                               String outboxId,
                                               long expectedVersion,
                                               long closedAt);
}
