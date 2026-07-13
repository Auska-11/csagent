package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;
import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateResult;

/**
 * 已保存的人工转接 Outbox 记录。
 *
 * <p>{@code version} 为后续状态更新提供 CAS 条件；两个实例同时处理一条记录时，只有版本匹配的
 * 更新能够成功，防止较慢实例覆盖较新结果。</p>
 */
public record PendingHandoffRecord(
        String outboxId,
        String userId,
        String conversationId,
        String reason,
        String summary,
        AfterSalesTicketCreateRequest request,
        PendingHandoffStatus status,
        String failureType,
        AfterSalesTicketCreateResult confirmedResult,
        int attemptCount,
        long nextAttemptAt,
        long version,
        long createdAt,
        long updatedAt
) {
}
