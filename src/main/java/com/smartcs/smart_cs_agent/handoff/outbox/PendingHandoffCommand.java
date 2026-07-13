package com.smartcs.smart_cs_agent.handoff.outbox;

import com.smartcs.smart_cs_agent.tools.AfterSalesTicketCreateRequest;

import java.util.Objects;

/**
 * 写入人工转接 Outbox 的命令。
 *
 * <p>除了远端请求，还保存本地用户、会话和转接原因，便于后续人工核对。这个命令会在外部调用
 * 之前写入，因此不包含失败信息；失败类型由后续状态更新单独记录。</p>
 */
public record PendingHandoffCommand(
        String tenantId,
        String userId,
        String conversationId,
        String reason,
        String summary,
        AfterSalesTicketCreateRequest request
) {

    public PendingHandoffCommand {
        requireText(tenantId, "tenantId");
        // 与数据库列和 PendingHandoffKey 使用同一规范值，避免 " tenant-a " 写入后却按
        // "tenant-a" 查询不到自己的记录。
        tenantId = tenantId.trim();
        requireText(userId, "userId");
        requireText(conversationId, "conversationId");
        requireText(reason, "reason");
        requireText(summary, "summary");
        Objects.requireNonNull(request, "request cannot be null");
        requireText(request.idempotencyKey(), "request.idempotencyKey");
        if (!request.idempotencyKey().equals(request.idempotencyKey().trim())) {
            // 幂等键还会原样写入远端 Header 和数据库。若这里只在复合键对象里 trim，memory 与 JDBC
            // 会使用不同的实际值，因此明确拒绝带首尾空白的输入比静默修正更安全。
            throw new IllegalArgumentException("request.idempotencyKey cannot have surrounding whitespace");
        }
        // 构造一次复合键即可统一执行非空和数据库列长度校验。
        new PendingHandoffKey(tenantId, request.idempotencyKey());
    }

    /**
     * 统一生成 Store 使用的租户复合键，避免调用方分别拼接 tenantId 和幂等键。
     */
    public PendingHandoffKey key() {
        return new PendingHandoffKey(tenantId, request.idempotencyKey());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }
}
